package giffer;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.List;

/** Gets a GIF to the clipboard, and from there into another application (see {@link PasteTarget}). */
final class PasteWorkflow {
    private static final String LABEL_WITHOUT_DEFAULT = "In Anwendung einfügen …";

    private final GifferApp app;
    private boolean pasteInProgress;

    /** Text of the paste buttons: names the default application, or invites to pick one. */
    final StringProperty buttonLabel = new SimpleStringProperty(LABEL_WITHOUT_DEFAULT);

    PasteWorkflow(GifferApp app) {
        this.app = app;
    }

    void updateButtonLabel() {
        String applicationName = PasteTarget.applicationName(app.settings.pasteProcessName);
        buttonLabel.set(applicationName.isEmpty() ? LABEL_WITHOUT_DEFAULT : "In " + applicationName + " einfügen");
    }

    /** Puts the GIF file on the clipboard (as a file, so chat apps attach it) and says so. */
    void copyToClipboard(GifEntry entry) {
        putOnClipboard(entry);
        app.toasts.show("„" + entry.displayName() + "“ kopiert – im Chat mit Strg+V einfügen", ToastLayer.Kind.SUCCESS);
    }

    private void putOnClipboard(GifEntry entry) {
        ClipboardContent content = new ClipboardContent();
        content.putFiles(List.of(app.library.gifFile(entry)));
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Pastes the GIF into the default application. The picker opens instead if no default is set, if the user
     * wants to be asked every time, or if {@code chooseApplication} is set (Shift+click).
     */
    void pasteIntoApplication(GifEntry entry, boolean chooseApplication) {
        if (!PasteTarget.isSupported()) {
            app.toasts.show("Das Einfügen in andere Anwendungen funktioniert nur unter Windows.", ToastLayer.Kind.ERROR);
            return;
        }
        Settings settings = app.settings;
        if (chooseApplication || settings.pasteProcessName.isBlank() || settings.askBeforePasting) {
            PasteDialog.show(app, entry);
        } else {
            pasteInto(entry, new PasteTarget.PasteRequest(0, settings.pasteProcessName, settings.pasteWindowTitleFilter));
        }
    }

    /** Clipboard, then bring the target to the front and press Ctrl+V; the result is reported in a toast. */
    void pasteInto(GifEntry entry, PasteTarget.PasteRequest request) {
        if (pasteInProgress) return;
        pasteInProgress = true;
        putOnClipboard(entry);
        String applicationName = PasteTarget.applicationName(request.processName());
        app.toasts.show("Füge „" + entry.displayName() + "“ in " + applicationName + " ein …", ToastLayer.Kind.INFO);
        app.backgroundThreads.submit(() -> {
            String failureText = null;
            try {
                PasteTarget.paste(request);
            } catch (Throwable failure) {
                failureText = ErrorText.of(failure);
            }
            String finishedFailureText = failureText;
            Platform.runLater(() -> {
                pasteInProgress = false;
                if (finishedFailureText == null) {
                    app.toasts.show("In " + applicationName + " eingefügt – ergänze Text und schick es ab", ToastLayer.Kind.SUCCESS);
                } else {
                    app.toasts.show(finishedFailureText + " Das GIF liegt in der Zwischenablage (Strg+V).", ToastLayer.Kind.ERROR);
                }
            });
        });
    }
}
