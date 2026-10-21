package giffer;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Short-lived notifications at the bottom centre. Never intercepts mouse events. */
final class ToastLayer extends VBox {

    enum Kind { INFO, SUCCESS, ERROR }

    private static final int MAX_VISIBLE_TOASTS = 3;

    ToastLayer() {
        setAlignment(Pos.BOTTOM_CENTER);
        setSpacing(8);
        setPadding(new Insets(0, 0, 28, 0));
        setMouseTransparent(true);
        setPickOnBounds(false);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(this, Pos.BOTTOM_CENTER);
    }

    void show(String message, Kind kind) {
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(560);
        text.getStyleClass().add("toast-text");

        Region icon = switch (kind) {
            case SUCCESS -> styledIcon("ok", "ok-icon");
            case ERROR -> styledIcon("error", "err-icon");
            case INFO -> styledIcon("info", "info-icon");
        };
        HBox toast = new HBox(10, icon, text);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().add("toast");
        toast.setOpacity(0);
        toast.setMaxWidth(Region.USE_PREF_SIZE);

        if (getChildren().size() >= MAX_VISIBLE_TOASTS) getChildren().remove(0);
        getChildren().add(toast);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(170), toast);
        fadeIn.setToValue(1);
        TranslateTransition slideUp = new TranslateTransition(Duration.millis(170), toast);
        slideUp.setFromY(10);
        slideUp.setToY(0);
        PauseTransition stayVisible = new PauseTransition(Duration.millis(kind == Kind.ERROR ? 5500 : 2800));
        FadeTransition fadeOut = new FadeTransition(Duration.millis(240), toast);
        fadeOut.setToValue(0);
        fadeIn.play();
        slideUp.play();
        SequentialTransition lifetime = new SequentialTransition(stayVisible, fadeOut);
        lifetime.setOnFinished(_ -> getChildren().remove(toast));
        lifetime.play();
    }

    private static Region styledIcon(String iconName, String styleClass) {
        Region icon = Ui.icon(iconName, 16);
        icon.getStyleClass().add(styleClass);
        return icon;
    }
}
