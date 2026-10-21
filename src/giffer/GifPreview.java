package giffer;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.io.File;

/** A rounded box that shows one GIF animated: the still thumbnail right away, the animation as soon as it is decoded. */
final class GifPreview extends StackPane {
    private static final double CORNER_DIAMETER = 24;

    private final GifferApp app;
    private final int maxEdgePixels;
    private final ImageView imageView = new ImageView();
    private final AnimationCache.Player player = new AnimationCache.Player();
    private GifEntry shownEntry;

    GifPreview(GifferApp app, double heightPixels, int maxEdgePixels) {
        this.app = app;
        this.maxEdgePixels = maxEdgePixels;
        getStyleClass().add("preview");
        setMinHeight(heightPixels);
        setPrefHeight(heightPixels);
        setMaxHeight(heightPixels);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(heightPixels);
        imageView.fitWidthProperty().bind(widthProperty());
        Region hairlineBorder = new Region(); // drawn above the image
        hairlineBorder.getStyleClass().add("preview-ring");
        hairlineBorder.setMouseTransparent(true);
        getChildren().addAll(imageView, hairlineBorder);

        Rectangle roundedClip = new Rectangle();
        roundedClip.setArcWidth(CORNER_DIAMETER);
        roundedClip.setArcHeight(CORNER_DIAMETER);
        roundedClip.widthProperty().bind(widthProperty());
        roundedClip.heightProperty().bind(heightProperty());
        setClip(roundedClip);
    }

    void show(GifEntry entry) {
        player.halt();
        imageView.setImage(null);
        shownEntry = entry;
        File thumbnailFile = app.library.thumbnailFile(entry);
        if (thumbnailFile.exists()) {
            imageView.setImage(new Image(thumbnailFile.toURI().toString(), maxEdgePixels, maxEdgePixels, true, true, false));
        }
        String cacheKey = entry.id + "@" + maxEdgePixels;
        app.animationCache.load(cacheKey, app.library.gifFile(entry), maxEdgePixels)
                .thenAccept(animation -> Platform.runLater(() -> {
                    if (shownEntry != entry) return; // something else was shown while this one was decoding
                    imageView.setImage(animation.frames()[0]);
                    player.play(imageView, animation);
                }))
                .exceptionally(_ -> null);
    }

    void clear() {
        player.halt();
        shownEntry = null;
        imageView.setImage(null);
    }
}
