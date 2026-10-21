package giffer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** GIF decoding helpers: composited frames (with disposal and delays), thumbnails and JPEG encoding. */
final class GifDecoder {
    private GifDecoder() {}

    record Size(int widthPixels, int heightPixels) {}

    record Frame(BufferedImage image, int delayMillis) {}

    private static final String GIF_STREAM_METADATA_FORMAT = "javax_imageio_gif_stream_1.0";
    private static final String GIF_IMAGE_METADATA_FORMAT = "javax_imageio_gif_image_1.0";
    /** Browsers play frames that claim a delay of 0 or 1 hundredth of a second at 100 ms. */
    private static final int DEFAULT_DELAY_HUNDREDTHS = 10;

    // ------------------------------------------------------------ cheap header read

    static Size readSize(File gifFile) throws IOException {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(gifFile)) {
            if (inputStream == null) throw new IOException("Datei nicht lesbar");
            ImageReader reader = createReader(inputStream);
            try {
                Size logicalScreen = readLogicalScreenSize(reader);
                return logicalScreen != null ? logicalScreen : new Size(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageReader createReader(ImageInputStream inputStream) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("Kein GIF-Decoder verfügbar");
        ImageReader reader = readers.next();
        reader.setInput(inputStream, false);
        return reader;
    }

    /** The canvas size declared in the GIF header, or null if it cannot be read. */
    private static Size readLogicalScreenSize(ImageReader reader) {
        try {
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata == null) return null;
            IIOMetadataNode root = (IIOMetadataNode) streamMetadata.getAsTree(GIF_STREAM_METADATA_FORMAT);
            IIOMetadataNode descriptor = (IIOMetadataNode) root.getElementsByTagName("LogicalScreenDescriptor").item(0);
            int width = Integer.parseInt(descriptor.getAttribute("logicalScreenWidth"));
            int height = Integer.parseInt(descriptor.getAttribute("logicalScreenHeight"));
            return width > 0 && height > 0 ? new Size(width, height) : null;
        } catch (Exception unreadableMetadata) {
            return null;
        }
    }

    // ------------------------------------------------------------ frame compositing

    /** Draws a GIF frame by frame onto one canvas, honouring frame offsets and disposal methods. */
    private static final class Compositor implements AutoCloseable {
        private ImageInputStream inputStream;
        private ImageReader reader;
        private BufferedImage canvas;
        private BufferedImage canvasBeforeFrame;
        private int frameIndex = -1;
        private String pendingDisposal = "none";
        private int lastFrameLeft;
        private int lastFrameTop;
        private int lastFrameWidth;
        private int lastFrameHeight;

        int frameCount;
        int canvasWidth;
        int canvasHeight;

        Compositor(File gifFile) throws IOException {
            inputStream = ImageIO.createImageInputStream(gifFile);
            if (inputStream == null) throw new IOException("Datei nicht lesbar");
            try {
                reader = createReader(inputStream);
                frameCount = reader.getNumImages(true);
                if (frameCount <= 0) throw new IOException("GIF enthält keine Frames");
                Size canvasSize = readLogicalScreenSize(reader);
                if (canvasSize == null) {
                    BufferedImage firstFrame = reader.read(0);
                    canvasSize = new Size(firstFrame.getWidth(), firstFrame.getHeight());
                }
                canvasWidth = canvasSize.widthPixels();
                canvasHeight = canvasSize.heightPixels();
                canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        BufferedImage canvas() {
            return canvas;
        }

        /** Applies the previous frame's disposal, draws the next frame and returns its delay in milliseconds. */
        int advance() throws IOException {
            applyPendingDisposal();
            frameIndex++;
            BufferedImage frame = reader.read(frameIndex);
            int frameLeft = 0;
            int frameTop = 0;
            int delayHundredths = DEFAULT_DELAY_HUNDREDTHS;
            String disposal = "none";
            try {
                IIOMetadata frameMetadata = reader.getImageMetadata(frameIndex);
                IIOMetadataNode root = (IIOMetadataNode) frameMetadata.getAsTree(GIF_IMAGE_METADATA_FORMAT);
                IIOMetadataNode descriptor = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
                frameLeft = Integer.parseInt(descriptor.getAttribute("imageLeftPosition"));
                frameTop = Integer.parseInt(descriptor.getAttribute("imageTopPosition"));
                var graphicControls = root.getElementsByTagName("GraphicControlExtension");
                if (graphicControls.getLength() > 0) {
                    IIOMetadataNode graphicControl = (IIOMetadataNode) graphicControls.item(0);
                    disposal = graphicControl.getAttribute("disposalMethod");
                    delayHundredths = Integer.parseInt(graphicControl.getAttribute("delayTime"));
                }
            } catch (Exception unusableMetadata) {
                // frames without usable metadata are drawn at 0/0 with the default delay
            }
            if (delayHundredths <= 1) delayHundredths = DEFAULT_DELAY_HUNDREDTHS;

            canvasBeforeFrame = "restoreToPrevious".equals(disposal) ? copyOf(canvas) : null;
            Graphics2D graphics = canvas.createGraphics();
            graphics.drawImage(frame, frameLeft, frameTop, null);
            graphics.dispose();
            pendingDisposal = disposal;
            lastFrameLeft = frameLeft;
            lastFrameTop = frameTop;
            lastFrameWidth = frame.getWidth();
            lastFrameHeight = frame.getHeight();
            return delayHundredths * 10;
        }

        private void applyPendingDisposal() {
            if (frameIndex < 0) return;
            if ("restoreToBackgroundColor".equals(pendingDisposal)) {
                Graphics2D graphics = canvas.createGraphics();
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(lastFrameLeft, lastFrameTop, lastFrameWidth, lastFrameHeight);
                graphics.dispose();
            } else if ("restoreToPrevious".equals(pendingDisposal) && canvasBeforeFrame != null) {
                canvas = canvasBeforeFrame;
            }
            pendingDisposal = "none";
            canvasBeforeFrame = null;
        }

        @Override
        public void close() {
            if (reader != null) reader.dispose();
            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
        }
    }

    /**
     * Returns {@code count} evenly spread, fully composited frames (unscaled copies).
     * With {@code count == 1} a frame from the first third is returned (the very first is often blank).
     */
    static List<BufferedImage> sampleFrames(File gifFile, int count) throws IOException {
        try (Compositor compositor = new Compositor(gifFile)) {
            Set<Integer> wantedIndexes = pickFrameIndexes(compositor.frameCount, count);
            int lastWantedIndex = Collections.max(wantedIndexes);
            List<BufferedImage> sampled = new ArrayList<>();
            for (int frameIndex = 0; frameIndex <= lastWantedIndex; frameIndex++) {
                try {
                    compositor.advance();
                } catch (IOException | RuntimeException unreadableFrame) {
                    if (frameIndex == 0) throw new IOException("GIF konnte nicht gelesen werden: " + unreadableFrame.getMessage());
                    break; // truncated file: use what we have
                }
                if (wantedIndexes.contains(frameIndex)) sampled.add(copyOf(compositor.canvas()));
            }
            if (sampled.isEmpty()) sampled.add(copyOf(compositor.canvas()));
            return sampled;
        }
    }

    /**
     * Decodes the whole animation, scaled so that no edge exceeds {@code maxEdgePixels}. Long GIFs are thinned
     * out (their delays are merged) to stay below {@code maxFrames} and roughly {@code maxPixelBytes}.
     */
    static List<Frame> decodeAnimation(File gifFile, int maxEdgePixels, int maxFrames, long maxPixelBytes) throws IOException {
        try (Compositor compositor = new Compositor(gifFile)) {
            double scale = Math.min(1.0, (double) maxEdgePixels / Math.max(compositor.canvasWidth, compositor.canvasHeight));
            long bytesPerFrame = Math.max(1, Math.round(compositor.canvasWidth * scale) * Math.round(compositor.canvasHeight * scale) * 4);
            int allowedFrames = (int) Math.max(1, Math.min(maxFrames, maxPixelBytes / bytesPerFrame));
            int keepEveryNthFrame = Math.max(1, (int) Math.ceil((double) compositor.frameCount / allowedFrames));

            List<Frame> frames = new ArrayList<>();
            BufferedImage pendingImage = null;
            int pendingDelayMillis = 0;
            for (int frameIndex = 0; frameIndex < compositor.frameCount; frameIndex++) {
                int frameDelayMillis;
                try {
                    frameDelayMillis = compositor.advance();
                } catch (IOException | RuntimeException unreadableFrame) {
                    if (pendingImage == null && frames.isEmpty()) {
                        throw new IOException("GIF konnte nicht gelesen werden: " + unreadableFrame.getMessage());
                    }
                    break;
                }
                if (frameIndex % keepEveryNthFrame == 0) {
                    if (pendingImage != null) frames.add(new Frame(pendingImage, pendingDelayMillis));
                    pendingImage = scaleKeepingTransparency(compositor.canvas(), maxEdgePixels);
                    pendingDelayMillis = frameDelayMillis;
                } else {
                    pendingDelayMillis += frameDelayMillis;
                }
            }
            if (pendingImage != null) frames.add(new Frame(pendingImage, pendingDelayMillis));
            return frames;
        }
    }

    private static Set<Integer> pickFrameIndexes(int frameCount, int wantedCount) {
        Set<Integer> indexes = new LinkedHashSet<>();
        if (wantedCount <= 1) {
            indexes.add(frameCount / 3);
            return indexes;
        }
        int count = Math.min(frameCount, wantedCount);
        if (count == 1) {
            indexes.add(0);
            return indexes;
        }
        for (int position = 0; position < count; position++) indexes.add(position * (frameCount - 1) / (count - 1));
        return indexes;
    }

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    // ------------------------------------------------------------ scaling / encoding

    /** Scales to fit into maxEdgePixels (never upscales), keeping transparency. */
    static BufferedImage scaleKeepingTransparency(BufferedImage source, int maxEdgePixels) {
        double scale = Math.min(1.0, (double) maxEdgePixels / Math.max(source.getWidth(), source.getHeight()));
        if (scale >= 1.0) return copyOf(source);
        int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        graphics.dispose();
        return scaled;
    }

    /** Scales to fit into maxEdgePixels (never upscales) on a white background, as an RGB image. */
    static BufferedImage scaleOnWhite(BufferedImage source, int maxEdgePixels) {
        double scale = Math.min(1.0, (double) maxEdgePixels / Math.max(source.getWidth(), source.getHeight()));
        int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, scaledWidth, scaledHeight);
        graphics.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        graphics.dispose();
        return scaled;
    }

    static byte[] encodeJpeg(BufferedImage rgbImage) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(rgbImage, "jpg", bytes);
        return bytes.toByteArray();
    }

    /** Writes a JPEG thumbnail unless one is already cached. */
    static void writeThumbnail(File gifFile, File thumbnailFile, int maxEdgePixels) throws IOException {
        if (thumbnailFile.exists()) return;
        BufferedImage thumbnail = scaleOnWhite(sampleFrames(gifFile, 1).get(0), maxEdgePixels);
        thumbnailFile.getParentFile().mkdirs();
        File temporaryFile = new File(thumbnailFile.getPath() + ".tmp");
        ImageIO.write(thumbnail, "jpg", temporaryFile);
        if (!temporaryFile.renameTo(thumbnailFile)) {
            // rename can fail on Windows if another thread won the race; the cache file then exists anyway
            temporaryFile.delete();
        }
    }
}
