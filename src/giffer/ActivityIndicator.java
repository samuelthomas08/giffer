package giffer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

/** The small pill at the bottom left that says what is going on in the background ("2 Links werden geladen"). */
final class ActivityIndicator extends HBox {
    private final Label text = new Label();
    private int pendingImports;
    private int pendingAnalyses;
    private Runnable onIdle = () -> {};

    ActivityIndicator() {
        super(10);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        getChildren().addAll(spinner, text);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("pill");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setMouseTransparent(true);
        setVisible(false);
        StackPane.setAlignment(this, Pos.BOTTOM_LEFT);
        StackPane.setMargin(this, new Insets(0, 0, 22, 22));
    }

    /** Runs whenever the last background job has finished. */
    void setOnIdle(Runnable onIdle) {
        this.onIdle = onIdle;
    }

    void changePendingImports(int delta) {
        pendingImports += delta;
        update();
    }

    void changePendingAnalyses(int delta) {
        pendingAnalyses += delta;
        update();
    }

    int pendingAnalyses() {
        return pendingAnalyses;
    }

    private void update() {
        List<String> parts = new ArrayList<>();
        if (pendingImports > 0) parts.add(pendingImports == 1 ? "1 Link wird geladen" : pendingImports + " Links werden geladen");
        if (pendingAnalyses > 0) parts.add(pendingAnalyses == 1 ? "1 GIF wird analysiert" : pendingAnalyses + " GIFs werden analysiert");
        setVisible(!parts.isEmpty());
        text.setText(String.join("  ·  ", parts));
        if (parts.isEmpty()) onIdle.run();
    }
}
