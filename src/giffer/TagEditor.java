package giffer;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Editable chip list: type and press Enter or comma to add, click the x (or Backspace on empty input) to remove. */
final class TagEditor extends FlowPane {
    private final List<String> tags = new ArrayList<>();
    private final TextField newTagInput = new TextField();
    private int chipCount;
    private boolean ignoringInputChanges;
    private Runnable onChange = () -> {};

    TagEditor(String promptText) {
        getStyleClass().add("tag-editor");
        setHgap(6);
        setVgap(6);
        newTagInput.getStyleClass().add("tag-input");
        newTagInput.setPromptText(promptText);
        newTagInput.setPrefWidth(112);
        getChildren().add(newTagInput);

        newTagInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                commitInput();
                event.consume();
            } else if (event.getCode() == KeyCode.BACK_SPACE && newTagInput.getText().isEmpty() && !tags.isEmpty()) {
                tags.remove(tags.size() - 1);
                rebuildChips();
                onChange.run();
            }
        });
        newTagInput.textProperty().addListener((_, _, newText) -> {
            boolean containsSeparator = newText.contains(",") || newText.contains(";") || newText.contains("\n");
            if (containsSeparator) commitInput();
        });
        newTagInput.focusedProperty().addListener((_, _, isFocused) -> {
            pseudoClassStateChanged(Ui.FOCUS_WITHIN, isFocused);
            if (!isFocused) commitInput();
        });
        setOnMouseClicked(_ -> newTagInput.requestFocus());
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Turns text still sitting in the input field into a tag. */
    void commitPendingInput() {
        commitInput();
    }

    void setTags(List<String> newTags) {
        tags.clear();
        tags.addAll(newTags);
        clearInputWithoutCommitting();
        rebuildChips();
    }

    List<String> getTags() {
        return new ArrayList<>(tags);
    }

    private void commitInput() {
        if (ignoringInputChanges) return;
        String inputText = newTagInput.getText();
        if (inputText.isEmpty()) return;

        boolean anyTagAdded = false;
        for (String part : inputText.split("[,;\\n]")) {
            String tag = part.strip();
            if (tag.isEmpty() || containsIgnoringCase(tag)) continue;
            tags.add(tag);
            anyTagAdded = true;
        }
        clearInputWithoutCommitting();
        if (anyTagAdded) {
            rebuildChips();
            onChange.run();
        }
    }

    private void clearInputWithoutCommitting() {
        ignoringInputChanges = true;
        newTagInput.clear();
        ignoringInputChanges = false;
    }

    private boolean containsIgnoringCase(String candidate) {
        String lowerCaseCandidate = candidate.toLowerCase(Locale.ROOT);
        for (String existing : tags) {
            if (existing.toLowerCase(Locale.ROOT).equals(lowerCaseCandidate)) return true;
        }
        return false;
    }

    /** Replaces only the chip nodes; the input stays in the scene so it keeps keyboard focus. */
    private void rebuildChips() {
        getChildren().remove(0, chipCount);
        List<HBox> chips = new ArrayList<>();
        for (String tag : tags) chips.add(createChip(tag));
        getChildren().addAll(0, chips);
        chipCount = chips.size();
    }

    private HBox createChip(String tag) {
        Label text = new Label(tag);
        text.getStyleClass().add("tag-text");
        Label removeButton = new Label();
        removeButton.setGraphic(Ui.icon("close", 12));
        removeButton.getStyleClass().add("tag-x");
        removeButton.setOnMouseClicked(event -> {
            tags.remove(tag);
            rebuildChips();
            onChange.run();
            event.consume();
        });
        HBox chip = new HBox(4, text, removeButton);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("tag");
        return chip;
    }
}
