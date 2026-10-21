package giffer;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * JavaFX shows only the first frame of a GIF, so animations are decoded here (frames and delays) and played
 * back by {@link Player}. Decoded animations are kept in a size-bounded least-recently-used cache.
 */
final class AnimationCache {

    /** A decoded animation: all frames as JavaFX images plus how long each one is shown. */
    record Animation(Image[] frames, int[] frameDelaysMillis, int widthPixels, int heightPixels) {
        long approximateBytes() {
            return (long) widthPixels * heightPixels * 4 * frames.length;
        }
    }

    private static final long CACHE_LIMIT_BYTES = 160L * 1024 * 1024;
    private static final int MAX_FRAMES = 240;
    private static final long MAX_BYTES_PER_ANIMATION = 56L * 1024 * 1024;

    private final ExecutorService decoderThreads;
    private final LinkedHashMap<String, Animation> cachedAnimations = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, CompletableFuture<Animation>> decodingInProgress = new HashMap<>();
    private long cachedBytes;

    AnimationCache(ExecutorService decoderThreads) {
        this.decoderThreads = decoderThreads;
    }

    /**
     * Completes with the decoded animation, or exceptionally if the file cannot be read. {@code cacheKey} must
     * identify both the GIF and {@code maxEdgePixels}, because the same GIF is cached once per size.
     */
    synchronized CompletableFuture<Animation> load(String cacheKey, File gifFile, int maxEdgePixels) {
        Animation cached = cachedAnimations.get(cacheKey);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<Animation> alreadyDecoding = decodingInProgress.get(cacheKey);
        if (alreadyDecoding != null) return alreadyDecoding;

        CompletableFuture<Animation> decoding = CompletableFuture.supplyAsync(() -> {
            try {
                return decode(gifFile, maxEdgePixels);
            } catch (IOException unreadableGif) {
                throw new CompletionException(unreadableGif);
            }
        }, decoderThreads);
        decodingInProgress.put(cacheKey, decoding);
        decoding.whenComplete((animation, _) -> {
            synchronized (AnimationCache.this) {
                decodingInProgress.remove(cacheKey);
                if (animation != null) {
                    cachedAnimations.put(cacheKey, animation);
                    cachedBytes += animation.approximateBytes();
                    evictLeastRecentlyUsed();
                }
            }
        });
        return decoding;
    }

    private void evictLeastRecentlyUsed() {
        Iterator<Map.Entry<String, Animation>> oldestFirst = cachedAnimations.entrySet().iterator();
        while (cachedBytes > CACHE_LIMIT_BYTES && cachedAnimations.size() > 1 && oldestFirst.hasNext()) {
            Animation oldest = oldestFirst.next().getValue();
            cachedBytes -= oldest.approximateBytes();
            oldestFirst.remove();
        }
    }

    private static Animation decode(File gifFile, int maxEdgePixels) throws IOException {
        List<GifDecoder.Frame> decodedFrames = GifDecoder.decodeAnimation(gifFile, maxEdgePixels, MAX_FRAMES, MAX_BYTES_PER_ANIMATION);
        Image[] frameImages = new Image[decodedFrames.size()];
        int[] frameDelays = new int[decodedFrames.size()];
        for (int index = 0; index < frameImages.length; index++) {
            frameImages[index] = Ui.toFxImage(decodedFrames.get(index).image());
            frameDelays[index] = decodedFrames.get(index).delayMillis();
        }
        return new Animation(frameImages, frameDelays, (int) frameImages[0].getWidth(), (int) frameImages[0].getHeight());
    }

    /** Plays an {@link Animation} on an ImageView, frame by frame, honouring each frame's delay. */
    static final class Player extends AnimationTimer {
        private ImageView target;
        private Animation animation;
        private int frameIndex;
        private long nextFrameAtNanos;

        /** The caller has already put frame 0 on the view. Animations with a single frame are not played. */
        void play(ImageView targetView, Animation animationToPlay) {
            halt();
            if (animationToPlay.frames().length < 2) return;
            target = targetView;
            animation = animationToPlay;
            frameIndex = 0;
            nextFrameAtNanos = 0;
            start();
        }

        void halt() {
            stop();
            animation = null;
            target = null;
        }

        boolean isPlaying() {
            return animation != null;
        }

        @Override
        public void handle(long nowNanos) {
            if (animation == null) return;
            if (nextFrameAtNanos == 0) {
                nextFrameAtNanos = nowNanos + millisToNanos(animation.frameDelaysMillis()[0]);
                return;
            }
            if (nowNanos < nextFrameAtNanos) return;
            frameIndex = (frameIndex + 1) % animation.frames().length;
            target.setImage(animation.frames()[frameIndex]);
            long frameDelayNanos = millisToNanos(animation.frameDelaysMillis()[frameIndex]);
            nextFrameAtNanos += frameDelayNanos;
            boolean fellBehind = nextFrameAtNanos < nowNanos; // e.g. window hidden: don't fast-forward afterwards
            if (fellBehind) nextFrameAtNanos = nowNanos + frameDelayNanos;
        }

        private static long millisToNanos(int millis) {
            return millis * 1_000_000L;
        }
    }
}
