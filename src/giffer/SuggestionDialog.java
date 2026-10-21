package giffer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "GIF vorschlagen": the user types a message or situation, Gemini picks the best GIFs of the library.
 * One dialog, four views: input, waiting, result (best pick large, up to two alternatives) and failure.
 */
final class SuggestionDialog {
    private static final double DIALOG_WIDTH = 600;
    private static final double PREVIEW_HEIGHT = 190;
    private static final int PREVIEW_EDGE_PIXELS = 560;
    private static final double TILE_IMAGE_WIDTH = 96;
    private static final double TILE_IMAGE_HEIGHT = 64;

    private final GifferApp app;
    private final TextArea messageArea = new TextArea();
    private final Label introduction;
    private final VBox content = new VBox(12);
    private final GifPreview preview;

    private final List<Node> alternativeTiles = new ArrayList<>();
    private List<SuggestionWorkflow.Match> shownMatches = List.of();
    private int selectedMatchIndex;
    private Label matchTitleLabel;
    private Label matchReasonLabel;

    /** Counts the searches; an answer is only shown if no newer search or view change happened since. */
    private int latestSearchNumber;

    static void show(GifferApp app) {
        new SuggestionDialog(app).display();
    }

    private SuggestionDialog(GifferApp app) {
        this.app = app;
        preview = new GifPreview(app, PREVIEW_HEIGHT, PREVIEW_EDGE_PIXELS);
        introduction = Ui.label("Gib eine Nachricht oder Situation ein, zum Beispiel die Nachricht, auf die du antworten "
                + "willst. Gemini wählt das passendste GIF aus deiner Bibliothek. Dafür werden nur Titel, Emotionen und "
                + "Stichwörter deiner GIFs gesendet, keine Bilder.", "muted");
        introduction.setWrapText(true);

        messageArea.getStyleClass().add("input");
        messageArea.setPromptText("z. B. „Ich hab die Präsentation komplett vergeigt …“");
        messageArea.setPrefRowCount(4);
        messageArea.setWrapText(true);
        messageArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                event.consume();
                startSearch();
            }
        });
    }

    private void display() {
        showInputView();
        VBox card = Ui.dialogCard("GIF vorschlagen", null, DIALOG_WIDTH, introduction, content);
        app.modal.show(card, () -> {
            latestSearchNumber++; // an answer that is still on its way is no longer wanted
            preview.clear();
        });
        messageArea.requestFocus();
    }

    // ------------------------------------------------------------ views

    private void showInputView() {
        latestSearchNumber++;
        preview.clear();
        setIntroductionVisible(true);
        Button cancelButton = Ui.button("Abbrechen", null, "outline", app.modal::close);
        Button findButton = Ui.button("Passendes GIF finden", "sparkle", "primary", this::startSearch);
        HBox footer = new HBox(8, Ui.label("Strg+Enter", "kbd"), Ui.horizontalSpacer(), cancelButton, findButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().setAll(messageArea, footer);
    }

    private void showWaitingView() {
        setIntroductionVisible(false);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        HBox waiting = new HBox(10, spinner, Ui.label("Gemini sucht das passende GIF …", "muted"));
        waiting.setAlignment(Pos.CENTER_LEFT);
        waiting.setPadding(new Insets(28, 0, 28, 0));
        content.getChildren().setAll(waiting, Ui.buttonRow(Ui.button("Abbrechen", null, "outline", this::showInputView)));
    }

    private void showFailureView(String failureText) {
        setIntroductionVisible(false);
        Label text = Ui.label(failureText, "muted");
        text.setWrapText(true);
        HBox message = new HBox(10, Ui.icon("warning", 16), text);
        message.getChildren().get(0).getStyleClass().add("warn-icon");
        message.setAlignment(Pos.CENTER_LEFT);
        message.getStyleClass().add("status-row");
        content.getChildren().setAll(message, Ui.buttonRow(
                Ui.button("Zurück", null, "outline", this::showInputView)));
    }

    private void showResultView(SuggestionWorkflow.Result result) {
        setIntroductionVisible(false);
        if (result.matches().isEmpty()) {
            showNothingFoundView(result);
            return;
        }
        shownMatches = result.matches();

        List<Node> views = new ArrayList<>();
        if (!result.interpretation().isBlank()) {
            Label interpretation = Ui.label("Verstanden als: " + result.interpretation(), "muted small");
            interpretation.setWrapText(true);
            views.add(interpretation);
        }
        views.add(preview);

        matchTitleLabel = Ui.label("", "pick-name");
        matchReasonLabel = Ui.label("", "muted small");
        matchReasonLabel.setWrapText(true);
        views.add(new VBox(2, matchTitleLabel, matchReasonLabel));

        alternativeTiles.clear();
        if (shownMatches.size() > 1) {
            HBox tiles = new HBox(8);
            for (int index = 0; index < shownMatches.size(); index++) {
                Node tile = createTile(shownMatches.get(index).entry(), index);
                alternativeTiles.add(tile);
                tiles.getChildren().add(tile);
            }
            views.add(tiles);
        }
        views.add(createActionRow());
        if (result.skippedWithoutAnalysis() > 0) {
            Label skippedNote = Ui.label(result.skippedWithoutAnalysis() + " GIF(s) ohne Beschreibung wurden nicht berücksichtigt.", "subtle small");
            views.add(skippedNote);
        }
        views.add(createResultFooter());
        content.getChildren().setAll(views);
        selectMatch(0);
    }

    private void showNothingFoundView(SuggestionWorkflow.Result result) {
        StringBuilder explanation = new StringBuilder("Nichts Passendes gefunden.");
        if (!result.interpretation().isBlank()) explanation.append(" Verstanden als: ").append(result.interpretation());
        explanation.append("\nFormuliere die Nachricht anders oder füge passende GIFs hinzu.");
        if (result.skippedWithoutAnalysis() > 0) {
            explanation.append("\n").append(result.skippedWithoutAnalysis()).append(" GIF(s) ohne Beschreibung wurden nicht berücksichtigt.");
        }
        Label text = Ui.label(explanation.toString(), "muted");
        text.setWrapText(true);
        HBox message = new HBox(10, Ui.icon("info", 16), text);
        message.setAlignment(Pos.TOP_LEFT);
        message.getStyleClass().add("status-row");
        content.getChildren().setAll(message, Ui.buttonRow(Ui.button("Andere Nachricht", null, "outline", this::showInputView)));
    }

    private void setIntroductionVisible(boolean visible) {
        introduction.setVisible(visible);
        introduction.setManaged(visible);
    }

    // ------------------------------------------------------------ result pieces

    private HBox createActionRow() {
        Button pasteButton = Ui.button(null, "send", "primary", null);
        pasteButton.textProperty().bind(app.pasteButtonLabel);
        Ui.setOnActionWithShift(pasteButton, shiftHeld -> {
            GifEntry entry = selectedEntry();
            app.modal.close();
            app.pasteIntoApplication(entry, shiftHeld);
        });
        Button copyButton = Ui.button("Kopieren", "copy", "outline", () -> {
            GifEntry entry = selectedEntry();
            app.modal.close();
            app.copyToClipboard(entry);
        });
        Button showInGridButton = Ui.button("Im Raster zeigen", null, "ghost", () -> {
            GifEntry entry = selectedEntry();
            app.modal.close();
            app.showEntryInResults(entry);
        });
        HBox actions = new HBox(8, pasteButton, copyButton, showInGridButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }

    private HBox createResultFooter() {
        Button anotherMessageButton = Ui.button("Andere Nachricht", null, "ghost small", this::showInputView);
        Button closeButton = Ui.button("Schließen", null, "outline small", app.modal::close);
        HBox footer = new HBox(8, anotherMessageButton, Ui.horizontalSpacer(), closeButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    /** A small clickable thumbnail with the GIF's title; the selected one is outlined. */
    private Node createTile(GifEntry entry, int matchIndex) {
        ImageView thumbnailView = new ImageView();
        thumbnailView.setPreserveRatio(true);
        thumbnailView.setSmooth(true);
        thumbnailView.setFitWidth(TILE_IMAGE_WIDTH);
        thumbnailView.setFitHeight(TILE_IMAGE_HEIGHT);
        loadThumbnail(thumbnailView, entry);
        StackPane imageBox = new StackPane(thumbnailView);
        imageBox.getStyleClass().add("tile-image");
        imageBox.setMinSize(TILE_IMAGE_WIDTH, TILE_IMAGE_HEIGHT);
        imageBox.setPrefSize(TILE_IMAGE_WIDTH, TILE_IMAGE_HEIGHT);
        imageBox.setMaxSize(TILE_IMAGE_WIDTH, TILE_IMAGE_HEIGHT);

        Label title = Ui.label(entry.displayName(), "small");
        title.setMaxWidth(TILE_IMAGE_WIDTH);
        VBox tile = new VBox(4, imageBox, title);
        tile.getStyleClass().add("alt-tile");
        tile.setOnMouseClicked(_ -> selectMatch(matchIndex));
        return tile;
    }

    private void loadThumbnail(ImageView view, GifEntry entry) {
        File thumbnailFile = app.library.thumbnailFile(entry);
        if (thumbnailFile.exists()) {
            view.setImage(thumbnailImage(thumbnailFile));
            return;
        }
        app.thumbnailThreads.submit(() -> {
            try {
                GifDecoder.writeThumbnail(app.library.gifFile(entry), thumbnailFile, GifLibrary.THUMBNAIL_EDGE_PIXELS);
                Platform.runLater(() -> view.setImage(thumbnailImage(thumbnailFile)));
            } catch (Exception unreadableGif) {
                // the tile just stays empty
            }
        });
    }

    private static Image thumbnailImage(File thumbnailFile) {
        return new Image(thumbnailFile.toURI().toString(), TILE_IMAGE_WIDTH * 2, TILE_IMAGE_HEIGHT * 2, true, true, true);
    }

    private void selectMatch(int matchIndex) {
        selectedMatchIndex = matchIndex;
        SuggestionWorkflow.Match match = shownMatches.get(matchIndex);
        preview.show(match.entry());
        matchTitleLabel.setText(match.entry().displayName());
        matchReasonLabel.setText(match.reason());
        matchReasonLabel.setVisible(!match.reason().isBlank());
        matchReasonLabel.setManaged(!match.reason().isBlank());
        for (int index = 0; index < alternativeTiles.size(); index++) {
            alternativeTiles.get(index).pseudoClassStateChanged(Ui.SELECTED, index == matchIndex);
        }
    }

    private GifEntry selectedEntry() {
        return shownMatches.get(selectedMatchIndex).entry();
    }

    // ------------------------------------------------------------ searching

    private void startSearch() {
        String message = messageArea.getText();
        int searchNumber = ++latestSearchNumber;
        showWaitingView();
        app.suggestions.find(message,
                result -> {
                    if (searchNumber == latestSearchNumber) showResultView(result);
                },
                failureText -> {
                    if (searchNumber == latestSearchNumber) showFailureView(failureText);
                });
    }
}
