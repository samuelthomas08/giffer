package giffer;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.File;
import java.util.List;

/** One GIF in the grid: still thumbnail, plays on hover, click selects, double-click copies, drag exports the file. */
final class GifCard extends StackPane implements MasonryPane.HeightForWidth {
    private static final double CORNER_DIAMETER = 24;
    private static final int HOVER_ANIMATION_EDGE_PIXELS = 380;
    private static final double MIN_ASPECT_RATIO = 0.55;
    private static final double MAX_ASPECT_RATIO = 2.4;

    final GifEntry entry;
    private final GifferApp app;
    private final ImageView imageView = new ImageView();
    private final Rectangle roundedClip = new Rectangle();
    private final Label titleLabel = new Label();
    private final VBox titleOverlay = new VBox();
    private final StackPane statusBadge = new StackPane();
    private final Button favoriteButton;
    private final Tooltip favoriteTooltip = new Tooltip();
    private final HBox hoverActions;
    private final AnimationCache.Player animationPlayer = new AnimationCache.Player();
    private Image thumbnail;
    private Tooltip statusBadgeTooltip;
    private boolean mouseIsOver;
    private boolean thumbnailRequested;

    GifCard(GifferApp app, GifEntry entry) {
        this.app = app;
        this.entry = entry;
        getStyleClass().add("card");

        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);

        titleLabel.getStyleClass().add("card-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleOverlay.getChildren().add(titleLabel);
        titleOverlay.getStyleClass().add("card-overlay");
        titleOverlay.setAlignment(Pos.BOTTOM_LEFT);
        titleOverlay.setMaxHeight(Region.USE_PREF_SIZE);
        titleOverlay.setMouseTransparent(true);
        titleOverlay.setOpacity(0);
        titleOverlay.setVisible(false);
        StackPane.setAlignment(titleOverlay, Pos.BOTTOM_CENTER);

        hoverActions = createHoverActions();
        StackPane.setAlignment(hoverActions, Pos.TOP_RIGHT);
        StackPane.setMargin(hoverActions, new Insets(8));

        statusBadge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        favoriteButton = createFavoriteButton();
        HBox topLeftControls = new HBox(6, favoriteButton, statusBadge);
        topLeftControls.setAlignment(Pos.CENTER_LEFT);
        topLeftControls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        topLeftControls.setPickOnBounds(false);
        StackPane.setAlignment(topLeftControls, Pos.TOP_LEFT);
        StackPane.setMargin(topLeftControls, new Insets(8));

        // selection outline: a separate node on top, because the image would otherwise cover the card's own border
        Region selectionRing = new Region();
        selectionRing.getStyleClass().add("card-ring");
        selectionRing.setMouseTransparent(true);

        getChildren().addAll(imageView, titleOverlay, selectionRing, topLeftControls, hoverActions);
        roundedClip.setArcWidth(CORNER_DIAMETER);
        roundedClip.setArcHeight(CORNER_DIAMETER);
        setClip(roundedClip);

        setOnMouseEntered(_ -> onHoverChanged(true));
        setOnMouseExited(_ -> onHoverChanged(false));
        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (event.getClickCount() >= 2) app.copyToClipboard(entry);
            else app.selectEntry(entry);
        });
        setOnDragDetected(event -> {
            Dragboard dragboard = startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(List.of(app.library.gifFile(entry)));
            dragboard.setContent(content);
            if (thumbnail != null) dragboard.setDragView(thumbnail);
            app.draggingFromGrid = true;
            event.consume();
        });
        setOnDragDone(_ -> app.draggingFromGrid = false);

        refresh();
    }

    /** The star in the top left corner: always visible, filled for a favourite, toggles on click. */
    private Button createFavoriteButton() {
        Button button = Ui.iconButton("star", null, () -> app.toggleFavorite(entry));
        favoriteTooltip.setShowDelay(Duration.millis(350));
        button.setTooltip(favoriteTooltip);
        button.getStyleClass().add("card-btn");
        button.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume); // don't also select the card
        return button;
    }

    /** The two buttons that fade in while the mouse is over the card: paste into an app, and copy. */
    private HBox createHoverActions() {
        Button pasteButton = Ui.iconButton("send", null, null);
        Ui.setOnActionWithShift(pasteButton, shiftHeld -> app.pasteIntoApplication(entry, shiftHeld));
        Tooltip pasteTooltip = new Tooltip();
        pasteTooltip.textProperty().bind(app.pasteButtonLabel.concat("\nUmschalt+Klick: andere Anwendung wählen"));
        pasteTooltip.setShowDelay(Duration.millis(350));
        pasteButton.setTooltip(pasteTooltip);

        Button copyButton = Ui.iconButton("copy", "In die Zwischenablage kopieren", () -> app.copyToClipboard(entry));
        HBox actions = new HBox(6, pasteButton, copyButton);
        actions.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        for (Button button : new Button[]{pasteButton, copyButton}) {
            button.getStyleClass().add("card-btn");
            button.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume); // don't also select the card
        }
        actions.setOpacity(0);
        actions.setVisible(false);
        return actions;
    }

    // ------------------------------------------------------------ state

    /** Updates title, status badge and selection outline after the entry changed. */
    void refresh() {
        titleLabel.setText(entry.displayName());
        Ui.setStarFilled(favoriteButton.getGraphic(), entry.favorite);
        favoriteTooltip.setText(entry.favorite ? "Aus Favoriten entfernen" : "Zu Favoriten hinzufügen");
        statusBadge.getChildren().clear();
        statusBadge.getStyleClass().removeAll("badge", "warn");
        if (statusBadgeTooltip != null) Tooltip.uninstall(statusBadge, statusBadgeTooltip);
        statusBadgeTooltip = null;
        statusBadge.setMouseTransparent(true);
        switch (entry.analysisStatus) {
            case ANALYZING -> {
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(16, 16);
                spinner.setMaxSize(16, 16);
                statusBadge.getChildren().add(spinner);
                statusBadge.getStyleClass().add("badge");
            }
            case ERROR -> showBadge("warning", "Analyse fehlgeschlagen: " + entry.analysisError, "warn");
            case NEW -> showBadge("sparkle", "Noch nicht von Gemini analysiert", null);
            case DONE -> { }
        }
        setSelected(app.selectedEntry == entry);
    }

    private void showBadge(String iconName, String tooltipText, String extraStyleClass) {
        statusBadge.getChildren().add(Ui.icon(iconName, 14));
        statusBadge.getStyleClass().add("badge");
        if (extraStyleClass != null) statusBadge.getStyleClass().add(extraStyleClass);
        statusBadgeTooltip = new Tooltip(tooltipText);
        Tooltip.install(statusBadge, statusBadgeTooltip);
        statusBadge.setMouseTransparent(false);
    }

    void setSelected(boolean isSelected) {
        pseudoClassStateChanged(Ui.SELECTED, isSelected);
    }

    // ------------------------------------------------------------ layout

    @Override
    public double heightForWidth(double columnWidth) {
        double aspectRatio = entry.aspectRatio();
        if (aspectRatio <= 0 && thumbnail != null && thumbnail.getHeight() > 0) {
            aspectRatio = thumbnail.getWidth() / thumbnail.getHeight();
        }
        if (aspectRatio <= 0) aspectRatio = 1.0;
        aspectRatio = Math.max(MIN_ASPECT_RATIO, Math.min(MAX_ASPECT_RATIO, aspectRatio));
        return Math.round(columnWidth / aspectRatio);
    }

    @Override
    protected void layoutChildren() {
        roundedClip.setWidth(getWidth());
        roundedClip.setHeight(getHeight());
        applyCoverCrop();
        super.layoutChildren();
    }

    /** Like CSS "object-fit: cover": fills the card and crops whatever overflows. */
    private void applyCoverCrop() {
        Image image = imageView.getImage();
        double cardWidth = getWidth();
        double cardHeight = getHeight();
        if (image == null || cardWidth <= 0 || cardHeight <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) return;

        double imageAspect = image.getWidth() / image.getHeight();
        double cardAspect = cardWidth / cardHeight;
        double cropWidth;
        double cropHeight;
        double cropLeft;
        double cropTop;
        if (imageAspect > cardAspect) { // image is wider than the card: crop left and right
            cropHeight = image.getHeight();
            cropWidth = cropHeight * cardAspect;
            cropLeft = (image.getWidth() - cropWidth) / 2;
            cropTop = 0;
        } else { // image is taller than the card: crop top and bottom
            cropWidth = image.getWidth();
            cropHeight = cropWidth / cardAspect;
            cropLeft = 0;
            cropTop = (image.getHeight() - cropHeight) / 2;
        }
        imageView.setViewport(new Rectangle2D(cropLeft, cropTop, cropWidth, cropHeight));
        imageView.setFitWidth(cardWidth);
        imageView.setFitHeight(cardHeight);
    }

    // ------------------------------------------------------------ thumbnail

    /** Loads (and if needed creates) the still image; harmless to call more than once. */
    void loadThumbnail() {
        if (thumbnailRequested) return;
        thumbnailRequested = true;
        File thumbnailFile = app.library.thumbnailFile(entry);
        if (thumbnailFile.exists()) {
            showThumbnail(thumbnailFile);
            return;
        }
        app.thumbnailThreads.submit(() -> {
            try {
                GifDecoder.writeThumbnail(app.library.gifFile(entry), thumbnailFile, GifLibrary.THUMBNAIL_EDGE_PIXELS);
                Platform.runLater(() -> showThumbnail(thumbnailFile));
            } catch (Exception unreadableGif) {
                Platform.runLater(() -> titleLabel.setText(entry.displayName() + " (defekt?)"));
            }
        });
    }

    private void showThumbnail(File thumbnailFile) {
        int edge = GifLibrary.THUMBNAIL_EDGE_PIXELS;
        Image loadedImage = new Image(thumbnailFile.toURI().toString(), edge, edge, true, true, true);
        Runnable onLoaded = () -> {
            if (loadedImage.isError()) return;
            thumbnail = loadedImage;
            if (entry.aspectRatio() <= 0) {
                entry.widthPixels = (int) Math.round(loadedImage.getWidth());
                entry.heightPixels = (int) Math.round(loadedImage.getHeight());
            }
            if (!mouseIsOver || !animationPlayer.isPlaying()) imageView.setImage(loadedImage);
            if (getParent() != null) getParent().requestLayout();
            requestLayout();
        };
        if (loadedImage.getProgress() >= 1.0) {
            onLoaded.run();
        } else {
            loadedImage.progressProperty().addListener((_, _, progress) -> {
                if (progress.doubleValue() >= 1.0) onLoaded.run();
            });
        }
    }

    // ------------------------------------------------------------ hover animation

    private void onHoverChanged(boolean isHovering) {
        mouseIsOver = isHovering;
        fadeVisibility(titleOverlay, isHovering);
        fadeVisibility(hoverActions, isHovering);
        if (isHovering) {
            String cacheKey = entry.id + "@" + HOVER_ANIMATION_EDGE_PIXELS;
            app.animationCache.load(cacheKey, app.library.gifFile(entry), HOVER_ANIMATION_EDGE_PIXELS)
                    .thenAccept(animation -> Platform.runLater(() -> {
                        if (!mouseIsOver) return; // the mouse left while the GIF was decoding
                        imageView.setImage(animation.frames()[0]);
                        applyCoverCrop();
                        animationPlayer.play(imageView, animation);
                    }))
                    .exceptionally(_ -> null);
        } else {
            animationPlayer.halt();
            if (thumbnail != null) {
                imageView.setImage(thumbnail);
                applyCoverCrop();
            }
        }
    }

    private static void fadeVisibility(Node node, boolean show) {
        if (show) node.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(140), node);
        fade.setToValue(show ? 1 : 0);
        if (!show) {
            fade.setOnFinished(_ -> {
                if (node.getOpacity() < 0.01) node.setVisible(false);
            });
        }
        fade.play();
    }
}
