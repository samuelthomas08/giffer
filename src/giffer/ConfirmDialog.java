package giffer;

import javafx.scene.control.Button;

/** "Are you sure?" dialog with a red confirm button. */
final class ConfirmDialog {
    private ConfirmDialog() {}

    static void show(GifferApp app, String title, String question, String confirmLabel, Runnable onConfirm) {
        Button cancelButton = Ui.button("Abbrechen", null, "outline", app.modal::close);
        Button confirmButton = Ui.button(confirmLabel, "delete", "danger-solid", () -> {
            app.modal.close();
            onConfirm.run();
        });
        app.modal.show(Ui.dialogCard(title, question, 420, Ui.buttonRow(cancelButton, confirmButton)), null);
    }
}
