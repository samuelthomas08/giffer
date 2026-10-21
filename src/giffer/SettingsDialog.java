package giffer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** The settings dialog with three tabs: Gemini (key, model), Einfügen (target application), Darstellung (theme). */
final class SettingsDialog {
    static final int TAB_GEMINI = 0;
    static final int TAB_PASTE = 1;
    static final int TAB_APPEARANCE = 2;

    private static final double DIALOG_WIDTH = 600;
    private static final double MAX_TAB_HEIGHT = 430;
    private static final double MIN_TAB_HEIGHT = 240;
    /** Space the dialog needs besides the tab content (title, tab bar, buttons, padding). */
    private static final double DIALOG_CHROME_HEIGHT = 250;

    private final GifferApp app;

    // Gemini tab
    private final PasswordField apiKeyHiddenField = new PasswordField();
    private final TextField modelField;
    private final ToggleSwitch analyzeAutomaticallySwitch;

    // paste tab
    private final WindowPicker windowPicker;
    private final TextField windowTitleFilterField;
    private final ToggleSwitch askBeforePastingSwitch;

    // appearance tab
    private final ToggleGroup themeGroup = new ToggleGroup();

    static void show(GifferApp app, int initialTab) {
        new SettingsDialog(app).display(initialTab);
    }

    private SettingsDialog(GifferApp app) {
        this.app = app;
        Settings settings = app.settings;
        apiKeyHiddenField.setText(settings.geminiApiKey);
        modelField = new TextField(settings.geminiModel);
        analyzeAutomaticallySwitch = new ToggleSwitch(settings.analyzeNewGifsAutomatically);
        windowPicker = new WindowPicker(settings.pasteProcessName, 190);
        windowTitleFilterField = new TextField(settings.pasteWindowTitleFilter);
        askBeforePastingSwitch = new ToggleSwitch(settings.askBeforePasting);
    }

    private void display(int initialTab) {
        VBox geminiTab = buildGeminiTab();
        VBox pasteTab = buildPasteTab();
        VBox appearanceTab = buildAppearanceTab();
        Node[] tabPanels = {geminiTab, pasteTab, appearanceTab};

        StackPane panels = new StackPane(tabPanels);
        ScrollPane panelScroll = new ScrollPane(panels);
        panelScroll.setFitToWidth(true);
        panelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroll.getStyleClass().add("detail-scroll");
        double panelHeight = Math.max(MIN_TAB_HEIGHT, Math.min(MAX_TAB_HEIGHT, app.root.getHeight() - DIALOG_CHROME_HEIGHT));
        panelScroll.setPrefHeight(panelHeight);
        panelScroll.setMinHeight(panelHeight);
        panelScroll.setMaxHeight(panelHeight);

        HBox tabBar = buildTabBar(tabPanels, panelScroll, initialTab);
        Button cancelButton = Ui.button("Abbrechen", null, "outline", app.modal::close);
        Button saveButton = Ui.button("Speichern", "check", "primary", this::saveAndClose);
        app.modal.show(Ui.dialogCard("Einstellungen", null, DIALOG_WIDTH, tabBar, panelScroll,
                Ui.buttonRow(cancelButton, saveButton)), null);
    }

    // ------------------------------------------------------------ tab bar

    private static HBox buildTabBar(Node[] tabPanels, ScrollPane panelScroll, int initialTab) {
        String[] tabNames = {"Gemini", "Einfügen", "Darstellung"};
        ToggleGroup tabGroup = new ToggleGroup();
        HBox tabBar = new HBox(2);
        tabBar.getStyleClass().add("seg");
        tabBar.setMaxWidth(Region.USE_PREF_SIZE);
        for (int tabIndex = 0; tabIndex < tabNames.length; tabIndex++) {
            ToggleButton tabButton = new ToggleButton(tabNames[tabIndex]);
            tabButton.setUserData(tabIndex);
            tabButton.setToggleGroup(tabGroup);
            tabButton.getStyleClass().add("seg-btn");
            tabButton.setFocusTraversable(false);
            tabBar.getChildren().add(tabButton);
        }
        tabGroup.selectedToggleProperty().addListener((_, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true); // always keep one tab selected
                return;
            }
            int shownTab = (Integer) selected.getUserData();
            for (int panelIndex = 0; panelIndex < tabPanels.length; panelIndex++) {
                tabPanels[panelIndex].setVisible(panelIndex == shownTab);
                tabPanels[panelIndex].setManaged(panelIndex == shownTab);
            }
            panelScroll.setVvalue(0);
        });
        tabGroup.getToggles().get(initialTab).setSelected(true);
        return tabBar;
    }

    // ------------------------------------------------------------ Gemini tab

    private VBox buildGeminiTab() {
        apiKeyHiddenField.setPromptText("API-Key einfügen");
        TextField apiKeyVisibleField = new TextField();
        apiKeyVisibleField.textProperty().bindBidirectional(apiKeyHiddenField.textProperty());
        apiKeyVisibleField.setPromptText("API-Key einfügen");
        apiKeyHiddenField.getStyleClass().add("input");
        apiKeyVisibleField.getStyleClass().add("input");
        apiKeyVisibleField.setVisible(false);
        CheckBox showKeyCheckBox = new CheckBox("Anzeigen");
        showKeyCheckBox.selectedProperty().addListener((_, _, show) -> {
            apiKeyVisibleField.setVisible(show);
            apiKeyHiddenField.setVisible(!show);
        });
        StackPane apiKeyFields = new StackPane(apiKeyHiddenField, apiKeyVisibleField);
        HBox.setHgrow(apiKeyFields, Priority.ALWAYS);
        HBox apiKeyRow = new HBox(12, apiKeyFields, showKeyCheckBox);
        apiKeyRow.setAlignment(Pos.CENTER_LEFT);

        modelField.getStyleClass().add("input");
        FlowPane modelPresets = new FlowPane(6, 6);
        for (String model : GeminiClient.MODELS) {
            Label presetChip = new Label(model);
            presetChip.getStyleClass().add("chip");
            presetChip.setOnMouseClicked(_ -> modelField.setText(model));
            modelPresets.getChildren().add(presetChip);
        }
        Runnable highlightActivePreset = () -> {
            for (Node chip : modelPresets.getChildren()) {
                chip.getStyleClass().remove("active");
                if (((Label) chip).getText().equals(modelField.getText().strip())) chip.getStyleClass().add("active");
            }
        };
        modelField.textProperty().addListener((_, _, _) -> highlightActivePreset.run());
        highlightActivePreset.run();

        HBox analyzeAutomaticallyRow = new HBox(10, analyzeAutomaticallySwitch,
                Ui.label("Neue GIFs automatisch mit Gemini analysieren"));
        analyzeAutomaticallyRow.setAlignment(Pos.CENTER_LEFT);

        Label privacyHint = Ui.label("Den Schlüssel erstellst du in Google AI Studio (aistudio.google.com/apikey). Er liegt "
                + "unverschlüsselt in " + app.library.settingsFile() + " (alternativ wird die Umgebungsvariable GEMINI_API_KEY "
                + "genutzt). Zur Analyse werden einige Frames jedes GIFs an die Gemini API von Google gesendet. Für „GIF "
                + "vorschlagen“ gehen deine Nachricht sowie Titel und Stichwörter der GIFs an Gemini, keine Bilder. "
                + "Im kostenlosen Kontingent darf Google diese Inhalte zur Verbesserung seiner Produkte nutzen.", "subtle small");
        privacyHint.setWrapText(true);

        return new VBox(14, Ui.labeledSection("Gemini API-Key", apiKeyRow), Ui.labeledSection("Modell", modelField, modelPresets),
                analyzeAutomaticallyRow, buildConnectionTestRow(), privacyHint);
    }

    /** "Verbindung testen": one tiny request with the key and model currently typed in. */
    private HBox buildConnectionTestRow() {
        Label resultLabel = new Label();
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(360);
        resultLabel.getStyleClass().add("small");
        Button testButton = Ui.button("Verbindung testen", "refresh", "outline", null);
        testButton.setOnAction(_ -> {
            Settings typedInSettings = new Settings();
            typedInSettings.geminiApiKey = apiKeyHiddenField.getText().strip();
            typedInSettings.geminiModel = modelOrDefault();
            resultLabel.getStyleClass().removeAll("ok-text", "err-text");
            resultLabel.setText("Teste …");
            testButton.setDisable(true);
            app.analysisThreads.submit(() -> {
                String message;
                boolean success;
                try {
                    message = "Verbunden – Gemini antwortet: " + GeminiClient.ping(typedInSettings);
                    success = true;
                } catch (Throwable failure) {
                    message = ErrorText.of(failure);
                    success = false;
                }
                String finishedMessage = message;
                boolean finishedSuccess = success;
                Platform.runLater(() -> {
                    resultLabel.setText(finishedMessage);
                    resultLabel.getStyleClass().add(finishedSuccess ? "ok-text" : "err-text");
                    testButton.setDisable(false);
                });
            });
        });
        HBox row = new HBox(12, testButton, resultLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private String modelOrDefault() {
        String model = modelField.getText().strip();
        return model.isEmpty() ? Settings.DEFAULT_GEMINI_MODEL : model;
    }

    // ------------------------------------------------------------ paste tab

    private VBox buildPasteTab() {
        Button rescanButton = Ui.button("Aktualisieren", "refresh", "ghost small", windowPicker::reload);
        Button clearSelectionButton = Ui.button("Auswahl aufheben", "close", "ghost small",
                () -> windowPicker.selectedWindowProperty().set(null));
        HBox pickerTools = new HBox(6, rescanButton, Ui.horizontalSpacer(), clearSelectionButton);
        pickerTools.setAlignment(Pos.CENTER_LEFT);

        windowTitleFilterField.getStyleClass().add("input");
        windowTitleFilterField.setPromptText("z. B. „Mattermost“, um in Browser-Fenstern den richtigen zu treffen");
        HBox askBeforePastingRow = new HBox(10, askBeforePastingSwitch, Ui.label("Vor jedem Einfügen die Anwendung abfragen"));
        askBeforePastingRow.setAlignment(Pos.CENTER_LEFT);

        Label introduction = Ui.label("Mit dem Papierflieger-Button wird das GIF in die Zwischenablage gelegt, diese Anwendung "
                + "nach vorn geholt und mit Strg+V eingefügt. Abgeschickt wird nichts. Mit gedrückter Umschalttaste "
                + "wählst du für einen Klick eine andere Anwendung.", "muted small");
        introduction.setWrapText(true);
        return new VBox(14, introduction, Ui.labeledSection("Standard-Anwendung", windowPicker, pickerTools),
                Ui.labeledSection("Fenstertitel enthält (optional)", windowTitleFilterField), askBeforePastingRow);
    }

    // ------------------------------------------------------------ appearance tab

    private VBox buildAppearanceTab() {
        HBox themeSegments = new HBox(2);
        themeSegments.getStyleClass().add("seg");
        themeSegments.setMaxWidth(Region.USE_PREF_SIZE);
        String[][] themes = {
                {Settings.THEME_SYSTEM, "System"}, {Settings.THEME_LIGHT, "Hell"}, {Settings.THEME_DARK, "Dunkel"}};
        for (String[] theme : themes) {
            ToggleButton themeButton = new ToggleButton(theme[1]);
            themeButton.setUserData(theme[0]);
            themeButton.setToggleGroup(themeGroup);
            themeButton.getStyleClass().add("seg-btn");
            themeButton.setSelected(theme[0].equals(app.settings.themeMode));
            themeButton.setFocusTraversable(false);
            themeSegments.getChildren().add(themeButton);
        }
        themeGroup.selectedToggleProperty().addListener((_, previous, selected) -> {
            if (selected == null && previous != null) previous.setSelected(true); // always keep one selected
        });
        return new VBox(14, Ui.labeledSection("Design", themeSegments));
    }

    // ------------------------------------------------------------ saving

    private void saveAndClose() {
        Settings settings = app.settings;
        settings.geminiApiKey = apiKeyHiddenField.getText().strip();
        settings.geminiModel = modelOrDefault();
        settings.analyzeNewGifsAutomatically = analyzeAutomaticallySwitch.isSelected();

        PasteTarget.WindowInfo chosenWindow = windowPicker.selectedWindowProperty().get();
        String newPasteProcessName = chosenWindow == null ? "" : chosenWindow.processName();
        boolean sameApplicationAsBefore = newPasteProcessName.equalsIgnoreCase(settings.pasteProcessName);
        boolean typedNewTitleFilter = !windowTitleFilterField.getText().isBlank();
        // a different default app invalidates the old title filter unless the user typed a new one
        settings.pasteWindowTitleFilter = sameApplicationAsBefore || typedNewTitleFilter ? windowTitleFilterField.getText().strip() : "";
        settings.pasteProcessName = newPasteProcessName;
        settings.askBeforePasting = askBeforePastingSwitch.isSelected();

        if (themeGroup.getSelectedToggle() != null) settings.themeMode = (String) themeGroup.getSelectedToggle().getUserData();

        app.saveSettings();
        app.paste.updateButtonLabel();
        app.applyTheme();
        app.modal.close();
        app.toasts.show("Einstellungen gespeichert", ToastLayer.Kind.SUCCESS);
        if (settings.hasGeminiApiKey() && settings.analyzeNewGifsAutomatically) app.analysis.analyzeAllPending();
        app.refreshResultStates();
    }
}
