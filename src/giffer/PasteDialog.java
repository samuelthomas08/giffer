package giffer;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/** "In welcher Anwendung einfügen?": the open windows to pick from, optionally remembered as the new default. */
final class PasteDialog {
    private PasteDialog() {}

    static void show(GifferApp app, GifEntry entry) {
        Settings settings = app.settings;
        WindowPicker picker = new WindowPicker(settings.pasteProcessName, 260);
        ToggleSwitch rememberSwitch = new ToggleSwitch(settings.pasteProcessName.isBlank());
        HBox rememberRow = new HBox(10, rememberSwitch, Ui.label("Als Standard-Anwendung merken"));
        rememberRow.setAlignment(Pos.CENTER_LEFT);

        Button rescanButton = Ui.button("Aktualisieren", "refresh", "ghost small", picker::reload);
        Button cancelButton = Ui.button("Abbrechen", null, "outline", app.modal::close);
        Button pasteButton = Ui.button("Einfügen", "send", "primary", null);
        // an app that is only remembered (not running) cannot be pasted into
        pasteButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> picker.selectedWindowProperty().get() == null || picker.selectedWindowProperty().get().processId() == 0,
                picker.selectedWindowProperty()));

        Runnable pasteIntoSelection = () -> {
            PasteTarget.WindowInfo window = picker.selectedWindowProperty().get();
            if (window == null || window.processId() == 0) return;
            if (rememberSwitch.isSelected()) {
                settings.pasteProcessName = window.processName();
                settings.pasteWindowTitleFilter = "";
                app.saveSettings();
                app.paste.updateButtonLabel();
            }
            app.modal.close();
            app.paste.pasteInto(entry, new PasteTarget.PasteRequest(window.processId(), window.processName(), ""));
        };
        pasteButton.setOnAction(_ -> pasteIntoSelection.run());
        picker.setOnActivate(pasteIntoSelection);

        HBox footer = new HBox(8, rescanButton, Ui.horizontalSpacer(), cancelButton, pasteButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        app.modal.show(Ui.dialogCard("In welcher Anwendung einfügen?",
                "Das GIF wird in die Zwischenablage gelegt, die gewählte Anwendung nach vorn geholt und mit Strg+V "
                        + "eingefügt. Abgeschickt wird nichts.",
                540, picker, rememberRow, footer), null);
    }
}
