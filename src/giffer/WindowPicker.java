package giffer;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Scrollable list of the open application windows; one row can be selected, a double-click confirms. */
final class WindowPicker extends VBox {
    private final VBox rowContainer = new VBox(2);
    private final ObjectProperty<PasteTarget.WindowInfo> selectedWindow = new SimpleObjectProperty<>();
    private final String defaultProcessName;
    private Runnable onActivate = () -> {};
    private int latestScanNumber;

    WindowPicker(String defaultProcessName, double heightPixels) {
        this.defaultProcessName = defaultProcessName == null ? "" : defaultProcessName;
        rowContainer.setPadding(new Insets(4));
        ScrollPane scrollPane = new ScrollPane(rowContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("picker-scroll");
        scrollPane.setMinHeight(heightPixels);
        scrollPane.setPrefHeight(heightPixels);
        scrollPane.setMaxHeight(heightPixels);
        getChildren().add(scrollPane);
        selectedWindow.addListener((_, _, _) -> markSelectedRow());
        reload();
    }

    ObjectProperty<PasteTarget.WindowInfo> selectedWindowProperty() {
        return selectedWindow;
    }

    /** Called when the user double-clicks a running application. */
    void setOnActivate(Runnable onActivate) {
        this.onActivate = onActivate;
    }

    /** Scans the open windows again (in the background). */
    void reload() {
        int scanNumber = ++latestScanNumber;
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        HBox loadingRow = new HBox(10, spinner, Ui.label("Suche geöffnete Fenster …", "muted"));
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingRow.setPadding(new Insets(10));
        rowContainer.getChildren().setAll(loadingRow);

        Thread scanThread = new Thread(() -> {
            List<PasteTarget.WindowInfo> foundWindows;
            String errorMessage = null;
            try {
                foundWindows = new ArrayList<>(PasteTarget.listWindows());
            } catch (Exception scanFailure) {
                foundWindows = List.of();
                errorMessage = scanFailure.getMessage();
            }
            List<PasteTarget.WindowInfo> windows = foundWindows;
            String error = errorMessage;
            Platform.runLater(() -> {
                // a newer scan may have been started in the meantime; its result wins
                if (scanNumber == latestScanNumber) showWindows(windows, error);
            });
        }, "giffer-window-scan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void showWindows(List<PasteTarget.WindowInfo> windows, String errorMessage) {
        PasteTarget.WindowInfo previousSelection = selectedWindow.get();
        selectedWindow.set(null);
        List<PasteTarget.WindowInfo> sortedWindows = new ArrayList<>(windows);
        sortedWindows.sort(Comparator.comparing((PasteTarget.WindowInfo window) -> !isDefaultApplication(window))
                .thenComparing(window -> window.applicationName().toLowerCase(Locale.ROOT))
                .thenComparing(window -> window.windowTitle().toLowerCase(Locale.ROOT)));
        // a remembered app that is not running stays visible and selected, so saving does not forget it
        boolean defaultIsRunning = sortedWindows.stream().anyMatch(this::isDefaultApplication);
        if (!defaultProcessName.isBlank() && !defaultIsRunning) {
            sortedWindows.add(0, new PasteTarget.WindowInfo(0, defaultProcessName, ""));
        }

        rowContainer.getChildren().clear();
        if (errorMessage != null) rowContainer.getChildren().add(createNote(errorMessage));
        else if (sortedWindows.isEmpty()) rowContainer.getChildren().add(createNote("Keine geöffneten Fenster gefunden."));
        PasteTarget.WindowInfo windowToSelect = null;
        for (PasteTarget.WindowInfo window : sortedWindows) {
            rowContainer.getChildren().add(createRow(window));
            if (windowToSelect == null && window.equals(previousSelection)) windowToSelect = window;
        }
        if (windowToSelect == null) windowToSelect = sortedWindows.stream().filter(this::isDefaultApplication).findFirst().orElse(null);
        selectedWindow.set(windowToSelect);
        markSelectedRow();
    }

    private boolean isDefaultApplication(PasteTarget.WindowInfo window) {
        return !defaultProcessName.isBlank() && window.processName().equalsIgnoreCase(defaultProcessName);
    }

    private Node createNote(String text) {
        Label note = Ui.label(text, "muted");
        note.setWrapText(true);
        note.setPadding(new Insets(10));
        return note;
    }

    private Node createRow(PasteTarget.WindowInfo window) {
        Label applicationName = Ui.label(window.applicationName(), "pick-name");
        String subtitle = window.processId() == 0 ? "läuft gerade nicht" : window.windowTitle();
        Label windowTitle = Ui.label(subtitle, "pick-title");
        windowTitle.setMaxWidth(Double.MAX_VALUE);
        VBox texts = new VBox(1, applicationName, windowTitle);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        if (isDefaultApplication(window)) row.getChildren().add(Ui.label("Standard", "soft-badge"));
        row.getStyleClass().add("pick-row");
        row.setUserData(window);
        row.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            selectedWindow.set(window);
            boolean isDoubleClickOnRunningApp = event.getClickCount() >= 2 && window.processId() > 0;
            if (isDoubleClickOnRunningApp) onActivate.run();
        });
        return row;
    }

    private void markSelectedRow() {
        for (Node row : rowContainer.getChildren()) {
            boolean isSelected = row.getUserData() != null && row.getUserData().equals(selectedWindow.get());
            row.pseudoClassStateChanged(Ui.SELECTED, isSelected);
        }
    }
}
