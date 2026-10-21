package giffer;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** shadcn-style toggle switch: 36x20 pill track with a 16px thumb. */
final class ToggleSwitch extends StackPane {
    private static final double TRACK_WIDTH = 36;
    private static final double TRACK_HEIGHT = 20;
    private static final double THUMB_SIZE = 16;
    private static final double TRACK_PADDING = 2;
    private static final double THUMB_TRAVEL = TRACK_WIDTH - 2 * TRACK_PADDING - THUMB_SIZE;

    private final BooleanProperty selected = new SimpleBooleanProperty();
    private final Region thumb = new Region();
    private final TranslateTransition slideAnimation = new TranslateTransition(Duration.millis(120), thumb);

    ToggleSwitch(boolean initiallySelected) {
        getStyleClass().add("switch");
        thumb.getStyleClass().add("switch-thumb");
        thumb.setMinSize(THUMB_SIZE, THUMB_SIZE);
        thumb.setPrefSize(THUMB_SIZE, THUMB_SIZE);
        thumb.setMaxSize(THUMB_SIZE, THUMB_SIZE);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0, TRACK_PADDING, 0, TRACK_PADDING));
        setMinSize(TRACK_WIDTH, TRACK_HEIGHT);
        setPrefSize(TRACK_WIDTH, TRACK_HEIGHT);
        setMaxSize(TRACK_WIDTH, TRACK_HEIGHT);
        getChildren().add(thumb);

        setOnMouseClicked(_ -> selected.set(!selected.get()));
        selected.addListener((_, _, isSelected) -> {
            pseudoClassStateChanged(Ui.SELECTED, isSelected);
            slideAnimation.stop();
            slideAnimation.setToX(isSelected ? THUMB_TRAVEL : 0);
            slideAnimation.play();
        });
        selected.set(initiallySelected);
        pseudoClassStateChanged(Ui.SELECTED, initiallySelected);
        thumb.setTranslateX(initiallySelected ? THUMB_TRAVEL : 0);
    }

    boolean isSelected() {
        return selected.get();
    }
}
