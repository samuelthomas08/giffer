package giffer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

/** "GIFs hinzufügen": paste links (one per line) or pick files and folders. */
final class ImportDialog {
    private ImportDialog() {}

    static void show(GifferApp app) {
        TextArea linksArea = new TextArea();
        linksArea.getStyleClass().add("input");
        linksArea.setPromptText("https://tenor.com/view/…\nhttps://giphy.com/gifs/…");
        linksArea.setPrefRowCount(5);
        linksArea.setWrapText(false);
        prefillWithLinksFromClipboard(linksArea);

        Button chooseFilesButton = Ui.button("Dateien …", "folder", "outline", () -> {
            app.modal.close();
            chooseFiles(app);
        });
        Button chooseFolderButton = Ui.button("Ordner …", "folder", "outline", () -> {
            app.modal.close();
            chooseFolder(app);
        });
        Button cancelButton = Ui.button("Abbrechen", null, "outline", app.modal::close);
        Button addButton = Ui.button("Hinzufügen", "add", "primary", () -> {
            List<String> links = LinkImporter.extractUrls(linksArea.getText());
            if (links.isEmpty()) {
                app.toasts.show("Ich erkenne hier keinen Link.", ToastLayer.Kind.ERROR);
                return;
            }
            app.modal.close();
            app.importing.importLinks(links);
        });
        HBox footer = new HBox(8, chooseFilesButton, chooseFolderButton, Ui.horizontalSpacer(), cancelButton, addButton);
        footer.setAlignment(Pos.CENTER_LEFT);

        Label supportedSites = Ui.label(
                "Unterstützt: Tenor, Giphy, Imgur, direkte .gif-Links und die meisten Seiten mit GIF-Vorschau.", "subtle small");
        supportedSites.setWrapText(true);
        app.modal.show(Ui.dialogCard("GIFs hinzufügen",
                "Füge Links ein, einen pro Zeile. Der Link der Tenor-Seite genügt, das GIF wird automatisch geladen.",
                560, linksArea, supportedSites, footer), null);
        linksArea.requestFocus();
        linksArea.positionCaret(linksArea.getText().length());
    }

    /** Links that are already on the clipboard are the most likely thing the user wants to add. */
    private static void prefillWithLinksFromClipboard(TextArea linksArea) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) return;
        List<String> links = LinkImporter.extractUrls(clipboard.getString());
        if (!links.isEmpty()) linksArea.setText(String.join("\n", links));
    }

    private static void chooseFiles(GifferApp app) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("GIF-Dateien auswählen");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GIF-Dateien", "*.gif"));
        List<File> files = fileChooser.showOpenMultipleDialog(app.stage);
        if (files != null && !files.isEmpty()) app.importing.importFiles(files);
    }

    private static void chooseFolder(GifferApp app) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Ordner mit GIFs auswählen");
        File folder = directoryChooser.showDialog(app.stage);
        if (folder != null) app.importing.importFiles(List.of(folder));
    }
}
