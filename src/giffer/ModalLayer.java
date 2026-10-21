package giffer;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** Dimmed overlay layer that hosts one dialog card; a click outside the card or Esc closes it. */
final class ModalLayer extends StackPane {
    private Runnable onClose;
    private FadeTransition fade;
    /** Logical state: false as soon as close() was called, even while the fade-out animation still runs. */
    private boolean open;

    ModalLayer() {
        getStyleClass().add("modal-layer");
        setVisible(false);
        setOnMousePressed(event -> {
            if (event.getTarget() == this) close();
        });
    }

    boolean isShowing() {
        return open;
    }

    /** Shows the card; {@code onClose} (may be null) runs once when the dialog is closed again. */
    void show(Node card, Runnable onClose) {
        if (fade != null) fade.stop();
        open = true;
        this.onClose = onClose;
        if (card instanceof Region region) region.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
        setOpacity(0);
        setVisible(true);
        fade = new FadeTransition(Duration.millis(140), this);
        fade.setToValue(1);
        fade.play();

        card.setScaleX(0.97);
        card.setScaleY(0.97);
        ScaleTransition grow = new ScaleTransition(Duration.millis(160), card);
        grow.setToX(1);
        grow.setToY(1);
        grow.play();
    }

    void close() {
        if (!open) return;
        open = false;
        Runnable closeCallback = onClose;
        onClose = null;
        if (fade != null) fade.stop();
        fade = new FadeTransition(Duration.millis(110), this);
        fade.setToValue(0);
        fade.setOnFinished(_ -> {
            setVisible(false);
            getChildren().clear();
        });
        fade.play();
        if (closeCallback != null) closeCallback.run();
    }
}
