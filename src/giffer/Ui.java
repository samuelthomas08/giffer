package giffer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Small UI factory helpers: icons (Lucide, ISC licence), buttons, labels, dialog building blocks. */
final class Ui {
    private Ui() {}

    static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    static final PseudoClass FOCUS_WITHIN = PseudoClass.getPseudoClass("focus-within");

    private static final double ICON_GRID_SIZE = 24;
    private static final double DEFAULT_ICON_SIZE = 16;

    /** Lucide icons on a 24x24 grid, drawn as 2px strokes (see {@code .icon} in app.css). */
    private static final Map<String, String> ICON_PATHS = new HashMap<>();

    private static String circlePath(double centerX, double centerY, double radius) {
        return "M" + (centerX - radius) + " " + centerY
                + "a" + radius + " " + radius + " 0 1 0 " + (2 * radius) + " 0"
                + "a" + radius + " " + radius + " 0 1 0 " + (-2 * radius) + " 0";
    }

    static {
        ICON_PATHS.put("search", circlePath(11, 11, 8) + "M21 21l-4.3-4.3");
        ICON_PATHS.put("add", "M5 12h14M12 5v14");
        ICON_PATHS.put("close", "M18 6 6 18M6 6l12 12");
        ICON_PATHS.put("settings", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" + circlePath(12, 12, 3));
        ICON_PATHS.put("copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2");
        ICON_PATHS.put("delete", "M3 6h18M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2M10 11v6M14 11v6");
        ICON_PATHS.put("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z");
        ICON_PATHS.put("open", "M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6");
        ICON_PATHS.put("check", "M20 6 9 17l-5-5");
        ICON_PATHS.put("sparkle", "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0zM20 3v4M22 5h-4M4 17v2M5 18H3");
        ICON_PATHS.put("refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5");
        ICON_PATHS.put("warning", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3M12 9v4M12 17h.01");
        ICON_PATHS.put("info", circlePath(12, 12, 10) + "M12 16v-4M12 8h.01");
        ICON_PATHS.put("ok", circlePath(12, 12, 10) + "M9 12l2 2 4-4");
        ICON_PATHS.put("error", circlePath(12, 12, 10) + "M15 9l-6 6M9 9l6 6");
        ICON_PATHS.put("play", circlePath(12, 12, 10) + "M10 8l6 4-6 4z");
        ICON_PATHS.put("send", "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11zM21.854 2.147l-10.94 10.939");
        ICON_PATHS.put("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12");
    }

    // ------------------------------------------------------------------ controls

    /** A crisp, CSS-colourable stroke icon, laid out as a square box of {@code sizePixels}. */
    static Pane icon(String name, double sizePixels) {
        SVGPath path = new SVGPath();
        path.setContent(ICON_PATHS.getOrDefault(name, ICON_PATHS.get("info")));
        path.getStyleClass().add("icon");
        Rectangle gridBounds = new Rectangle(ICON_GRID_SIZE, ICON_GRID_SIZE); // fixes the group's bounds to the grid
        gridBounds.setOpacity(0);
        Group scaledIcon = new Group(gridBounds, path);
        double scale = sizePixels / ICON_GRID_SIZE;
        scaledIcon.getTransforms().add(new Scale(scale, scale, 0, 0));
        Pane box = new Pane(scaledIcon);
        box.setMinSize(sizePixels, sizePixels);
        box.setPrefSize(sizePixels, sizePixels);
        box.setMaxSize(sizePixels, sizePixels);
        box.setMouseTransparent(true);
        return box;
    }

    /** {@code styleVariants} is space separated, e.g. "primary", "outline", "ghost small", "danger". */
    static Button button(String text, String iconName, String styleVariants, Runnable action) {
        Button button = new Button(text);
        if (iconName != null) {
            button.setGraphic(icon(iconName, DEFAULT_ICON_SIZE));
            button.setGraphicTextGap(8);
        }
        if (styleVariants != null) button.getStyleClass().addAll(styleVariants.split(" "));
        if (action != null) button.setOnAction(_ -> action.run());
        button.setFocusTraversable(false);
        return button;
    }

    static Button iconButton(String iconName, String tooltipText, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon(iconName, DEFAULT_ICON_SIZE));
        button.getStyleClass().add("icon-btn");
        if (tooltipText != null) {
            Tooltip tooltip = new Tooltip(tooltipText);
            tooltip.setShowDelay(Duration.millis(350));
            button.setTooltip(tooltip);
        }
        if (action != null) button.setOnAction(_ -> action.run());
        button.setFocusTraversable(false);
        return button;
    }

    /** Button action that also reports whether Shift was held when the button was pressed. */
    static void setOnActionWithShift(Button button, Consumer<Boolean> action) {
        boolean[] shiftWasHeld = new boolean[1];
        button.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> shiftWasHeld[0] = event.isShiftDown());
        button.setOnAction(_ -> {
            boolean shift = shiftWasHeld[0];
            shiftWasHeld[0] = false;
            action.accept(shift);
        });
    }

    /** {@code styleClasses} entries may themselves contain several space-separated classes. */
    static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        for (String classes : styleClasses) label.getStyleClass().addAll(classes.split(" "));
        return label;
    }

    /** The little "GIF" tile in the header. */
    static StackPane logo() {
        Label text = new Label("GIF");
        text.getStyleClass().add("logo-text");
        StackPane tile = new StackPane(text);
        tile.getStyleClass().add("logo");
        tile.setMinSize(26, 26);
        tile.setPrefSize(26, 26);
        tile.setMaxSize(26, 26);
        return tile;
    }

    // ------------------------------------------------------------------ dialog building blocks

    /** A dialog card with a title, an optional grey explanation and the given content. */
    static VBox dialogCard(String title, String description, double width, Node... content) {
        VBox card = new VBox(16);
        card.getStyleClass().add("modal");
        card.setPrefWidth(width);
        card.setMinWidth(width);
        card.getChildren().add(label(title, "modal-title"));
        if (description != null) {
            Label descriptionLabel = label(description, "muted");
            descriptionLabel.setWrapText(true);
            card.getChildren().add(descriptionLabel);
        }
        card.getChildren().addAll(content);
        return card;
    }

    /** Right-aligned row of buttons. */
    static HBox buttonRow(Node... buttons) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().addAll(buttons);
        return row;
    }

    /** A bold caption above the given controls. */
    static VBox labeledSection(String caption, Node... controls) {
        VBox section = new VBox(8);
        section.getChildren().add(label(caption, "field-label"));
        section.getChildren().addAll(controls);
        return section;
    }

    /** An empty region that soaks up the remaining width of an HBox. */
    static Region horizontalSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    // ------------------------------------------------------------------ images

    /** AWT to JavaFX without needing the javafx.swing module. */
    static WritableImage toFxImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }

    static Image appIcon(int sizePixels) {
        return toFxImage(AppIcon.render(sizePixels));
    }
}
