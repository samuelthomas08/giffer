package giffer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Right-hand side panel: animated preview, quick actions and the editable Gemini analysis. */
final class DetailPanel extends VBox {
    private static final double PANEL_WIDTH = 390;
    private static final int PREVIEW_EDGE_PIXELS = 560;
    private static final double PREVIEW_HEIGHT = 250;

    private final GifferApp app;
    private GifEntry shownEntry;
    private boolean populatingFields;

    private final ScrollPane contentScroll;
    private final GifPreview preview;

    private final TextField titleField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final TagEditor emotionsEditor = new TagEditor("+ Hinzufügen");
    private final TagEditor keywordsEditor = new TagEditor("+ Hinzufügen");
    private final TextArea usageHintArea = new TextArea();
    private final TextField textInGifField = new TextField();

    private final HBox statusRow = new HBox(10);
    private final Label statusText = new Label();
    private final Button analyzeButton = new Button();
    private final Button openSourceButton;
    private final Tooltip favoriteTooltip = new Tooltip();
    private Button favoriteButton;

    DetailPanel(GifferApp app) {
        this.app = app;
        getStyleClass().add("detail");
        setMinWidth(PANEL_WIDTH);
        setPrefWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);

        preview = new GifPreview(app, PREVIEW_HEIGHT, PREVIEW_EDGE_PIXELS);
        openSourceButton = Ui.button("Quelle", "open", "outline small", () -> {
            if (shownEntry != null) app.openSourcePage(shownEntry);
        });
        configureAnalyzeButton();
        configureFields();

        VBox content = new VBox(14,
                preview, createSendButtons(), createSecondaryActions(), statusRow,
                Ui.labeledSection("Titel", titleField),
                Ui.labeledSection("Beschreibung", descriptionArea),
                Ui.labeledSection("Emotionen", emotionsEditor),
                Ui.labeledSection("Stichwörter", keywordsEditor),
                Ui.labeledSection("Wann benutzen?", usageHintArea),
                Ui.labeledSection("Text im GIF", textInGifField));
        content.setPadding(new Insets(6, 20, 28, 20));

        contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.getStyleClass().add("detail-scroll");
        VBox.setVgrow(contentScroll, Priority.ALWAYS);

        getChildren().addAll(createHeader(), contentScroll);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getStyleClass().add("status-row");
    }

    // ------------------------------------------------------------ building the panel

    private Node createHeader() {
        Label title = Ui.label("Details", "detail-title");
        favoriteButton = Ui.iconButton("star", null, () -> {
            if (shownEntry != null) app.toggleFavorite(shownEntry);
        });
        favoriteTooltip.setShowDelay(Duration.millis(350));
        favoriteButton.setTooltip(favoriteTooltip);
        Button closeButton = Ui.iconButton("close", "Schließen (Esc)", () -> app.selectEntry(null));
        HBox header = new HBox(4, title, Ui.horizontalSpacer(), favoriteButton, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 14, 6, 20));
        return header;
    }

    /** "Paste into <app>" (the main action) above "copy to clipboard". */
    private Node createSendButtons() {
        Button pasteButton = Ui.button(null, "send", "primary", null);
        pasteButton.textProperty().bind(app.pasteButtonLabel);
        Ui.setOnActionWithShift(pasteButton, shiftHeld -> {
            if (shownEntry != null) app.pasteIntoApplication(shownEntry, shiftHeld);
        });
        Tooltip pasteTooltip = new Tooltip("Umschalt+Klick: andere Anwendung wählen");
        pasteTooltip.setShowDelay(Duration.millis(500));
        pasteButton.setTooltip(pasteTooltip);

        Button copyButton = Ui.button("In Zwischenablage kopieren", "copy", "outline", () -> {
            if (shownEntry != null) app.copyToClipboard(shownEntry);
        });
        pasteButton.setMaxWidth(Double.MAX_VALUE);
        copyButton.setMaxWidth(Double.MAX_VALUE);
        return new VBox(8, pasteButton, copyButton);
    }

    private Node createSecondaryActions() {
        Button revealButton = Ui.button("Ordner", "folder", "outline small", () -> {
            if (shownEntry != null) app.revealInExplorer(shownEntry);
        });
        Button deleteButton = Ui.button("Löschen", "delete", "danger small", () -> {
            if (shownEntry != null) app.deleteEntry(shownEntry);
        });
        HBox actions = new HBox(6, revealButton, openSourceButton, Ui.horizontalSpacer(), deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }

    private void configureAnalyzeButton() {
        statusText.setWrapText(true);
        statusText.getStyleClass().addAll("muted", "small");
        HBox.setHgrow(statusText, Priority.ALWAYS);
        statusText.setMaxWidth(Double.MAX_VALUE);
        analyzeButton.getStyleClass().addAll("button", "ghost", "small");
        analyzeButton.setFocusTraversable(false);
        analyzeButton.setMinWidth(Region.USE_PREF_SIZE); // the status text wraps, the button must not shrink
        analyzeButton.setOnAction(_ -> {
            if (shownEntry != null) app.analysis.analyze(shownEntry);
        });
    }

    /** Every edit goes straight into the entry; the app saves it a moment later. */
    private void configureFields() {
        for (TextField field : new TextField[]{titleField, textInGifField}) field.getStyleClass().add("input");
        for (TextArea area : new TextArea[]{descriptionArea, usageHintArea}) {
            area.getStyleClass().add("input");
            area.setWrapText(true);
            area.setPrefRowCount(3);
        }
        titleField.textProperty().addListener((_, _, text) -> applyEdit(() -> shownEntry.title = text.strip()));
        descriptionArea.textProperty().addListener((_, _, text) -> applyEdit(() -> shownEntry.description = text.strip()));
        usageHintArea.textProperty().addListener((_, _, text) -> applyEdit(() -> shownEntry.usageHint = text.strip()));
        textInGifField.textProperty().addListener((_, _, text) -> applyEdit(() -> shownEntry.textInGif = text.strip()));
        emotionsEditor.setOnChange(() -> applyEdit(() -> shownEntry.emotions = emotionsEditor.getTags()));
        keywordsEditor.setOnChange(() -> applyEdit(() -> shownEntry.keywords = keywordsEditor.getTags()));
    }

    private void applyEdit(Runnable change) {
        if (populatingFields || shownEntry == null) return;
        change.run();
        app.onEntryEdited(shownEntry);
    }

    // ------------------------------------------------------------ content

    /** Shows an entry: fields, status and the animated preview. */
    void show(GifEntry entry) {
        boolean isDifferentEntry = shownEntry != entry;
        shownEntry = entry;
        updateFields(entry);
        preview.show(entry);
        if (isDifferentEntry) contentScroll.setVvalue(0);
    }

    /** Refreshes fields and status of the shown entry without touching the preview. */
    void updateFields(GifEntry entry) {
        if (entry != shownEntry) return;
        populatingFields = true;
        titleField.setText(entry.title);
        descriptionArea.setText(entry.description);
        emotionsEditor.setTags(entry.emotions);
        keywordsEditor.setTags(entry.keywords);
        usageHintArea.setText(entry.usageHint);
        textInGifField.setText(entry.textInGif);
        populatingFields = false;
        boolean hasSourcePage = !entry.sourceUrl.isBlank();
        openSourceButton.setVisible(hasSourcePage);
        openSourceButton.setManaged(hasSourcePage);
        refreshFavorite(entry);
        refreshStatus();
    }

    /** Updates only the star in the header (the text fields may be in the middle of an edit). */
    void refreshFavorite(GifEntry entry) {
        if (entry != shownEntry) return;
        Ui.setStarFilled(favoriteButton.getGraphic(), entry.favorite);
        favoriteTooltip.setText(entry.favorite ? "Aus Favoriten entfernen" : "Zu Favoriten hinzufügen");
    }

    void clear() {
        preview.clear();
        shownEntry = null;
    }

    /** Applies half-typed tags before the selection changes. */
    void commitPendingEdits() {
        if (shownEntry == null) return;
        emotionsEditor.commitPendingInput();
        keywordsEditor.commitPendingInput();
    }

    void refreshStatus() {
        GifEntry entry = shownEntry;
        statusRow.getChildren().clear();
        if (entry == null) return;
        switch (entry.analysisStatus) {
            case ANALYZING -> {
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(18, 18);
                spinner.setMaxSize(18, 18);
                statusText.setText("Gemini analysiert dieses GIF …");
                statusRow.getChildren().addAll(spinner, statusText);
            }
            case ERROR -> {
                statusText.setText("Analyse fehlgeschlagen: " + entry.analysisError);
                analyzeButton.setText("Erneut versuchen");
                showStatus("warning", "warn-icon");
            }
            case NEW -> {
                boolean canAnalyze = app.settings.hasGeminiApiKey();
                statusText.setText(canAnalyze ? "Noch nicht analysiert." : "Noch nicht analysiert – API-Key fehlt (Einstellungen).");
                analyzeButton.setText("Analysieren");
                showStatus("sparkle", null);
            }
            case DONE -> {
                statusText.setText("Von Gemini analysiert");
                analyzeButton.setText("Neu analysieren");
                showStatus("check", "ok-icon");
            }
        }
    }

    private void showStatus(String iconName, String iconStyleClass) {
        Node icon = Ui.icon(iconName, 16);
        if (iconStyleClass != null) icon.getStyleClass().add(iconStyleClass);
        statusRow.getChildren().addAll(icon, statusText, analyzeButton);
    }
}
