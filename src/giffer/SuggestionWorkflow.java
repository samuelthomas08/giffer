package giffer;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Picks the GIFs of the library that fit a chat message. Gemini only sees the texts it wrote about each GIF
 * (title, emotions, keywords, usage hint), so no image is sent and one cheap text request is enough.
 */
final class SuggestionWorkflow {
    /** More GIFs than this are not sent; the ones that share words with the message come first, then the newest. */
    static final int MAX_CATALOG_ENTRIES = 600;
    static final int MAX_MESSAGE_LENGTH = 2000;

    private static final int MAX_KEYWORDS_PER_ITEM = 12;
    private static final int MAX_USAGE_HINT_LENGTH = 140;

    /** A suggested GIF with the model's reason. */
    record Match(GifEntry entry, String reason) {}

    /** {@code skippedWithoutAnalysis} counts library GIFs that had no text yet and therefore could not be considered. */
    record Result(String interpretation, List<Match> matches, int skippedWithoutAnalysis) {}

    private final GifferApp app;

    SuggestionWorkflow(GifferApp app) {
        this.app = app;
    }

    /** Runs on the UI thread, answers on the UI thread: exactly one of the two callbacks is called. */
    void find(String message, Consumer<Result> onResult, Consumer<String> onFailure) {
        String trimmedMessage = message.strip();
        if (trimmedMessage.isEmpty()) {
            onFailure.accept("Bitte gib zuerst eine Nachricht oder Situation ein.");
            return;
        }
        if (!app.settings.hasGeminiApiKey()) {
            onFailure.accept("Kein Gemini API-Key hinterlegt – bitte in den Einstellungen eintragen.");
            return;
        }
        String limitedMessage = trimmedMessage.length() > MAX_MESSAGE_LENGTH
                ? trimmedMessage.substring(0, MAX_MESSAGE_LENGTH) : trimmedMessage;

        List<GifEntry> describedEntries = new ArrayList<>();
        int skippedCount = selectCatalogEntries(limitedMessage, describedEntries);
        if (describedEntries.isEmpty()) {
            onFailure.accept("Noch kein GIF hat eine Beschreibung. Füge GIFs hinzu und lass sie von Gemini beschriften "
                    + "(mit API-Key passiert das automatisch).");
            return;
        }
        List<GeminiClient.CatalogItem> catalog = buildCatalog(describedEntries);
        Settings settingsSnapshot = app.settings.copy();
        app.analysisThreads.submit(() -> {
            GeminiClient.Suggestion suggestion = null;
            String failureText = null;
            try {
                suggestion = GeminiClient.suggest(limitedMessage, catalog, settingsSnapshot);
            } catch (Throwable failure) {
                failureText = ErrorText.of(failure);
            }
            GeminiClient.Suggestion finishedSuggestion = suggestion;
            String finishedFailureText = failureText;
            Platform.runLater(() -> {
                if (finishedSuggestion == null) {
                    onFailure.accept(finishedFailureText);
                    return;
                }
                List<Match> matches = new ArrayList<>();
                for (GeminiClient.SuggestedPick pick : finishedSuggestion.picks()) {
                    matches.add(new Match(describedEntries.get(pick.number() - 1), pick.reason()));
                }
                onResult.accept(new Result(finishedSuggestion.interpretation(), matches, skippedCount));
            });
        });
    }

    /**
     * Fills {@code selected} with the entries to send (those that have any text, at most
     * {@link #MAX_CATALOG_ENTRIES}) and returns how many entries were left out for having no text at all.
     */
    private int selectCatalogEntries(String message, List<GifEntry> selected) {
        List<GifEntry> candidates = new ArrayList<>();
        int skippedCount = 0;
        for (GifEntry entry : app.library.search("")) { // newest first
            if (hasDescription(entry)) candidates.add(entry);
            else skippedCount++;
        }
        if (candidates.size() <= MAX_CATALOG_ENTRIES) {
            selected.addAll(candidates);
            return skippedCount;
        }
        // too many: entries that share words with the message first, then favourites, then the newest ones
        Set<GifEntry> chosen = new HashSet<>();
        for (GifEntry entry : app.library.search(message)) {
            if (selected.size() >= MAX_CATALOG_ENTRIES / 2) break;
            if (candidates.contains(entry) && chosen.add(entry)) selected.add(entry);
        }
        for (boolean favoritesPass : new boolean[]{true, false}) {
            for (GifEntry entry : candidates) {
                if (selected.size() >= MAX_CATALOG_ENTRIES) break;
                if (entry.favorite == favoritesPass && chosen.add(entry)) selected.add(entry);
            }
        }
        return skippedCount;
    }

    private static boolean hasDescription(GifEntry entry) {
        return !entry.title.isBlank() || !entry.description.isBlank() || !entry.keywords.isEmpty() || !entry.emotions.isEmpty();
    }

    /** Numbers start at 1 and follow the order of {@code entries}, which is how the answer is mapped back. */
    private static List<GeminiClient.CatalogItem> buildCatalog(List<GifEntry> entries) {
        List<GeminiClient.CatalogItem> catalog = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            GifEntry entry = entries.get(index);
            List<String> keywords = entry.keywords.size() > MAX_KEYWORDS_PER_ITEM
                    ? entry.keywords.subList(0, MAX_KEYWORDS_PER_ITEM) : entry.keywords;
            String usageHint = entry.usageHint.length() > MAX_USAGE_HINT_LENGTH
                    ? entry.usageHint.substring(0, MAX_USAGE_HINT_LENGTH) : entry.usageHint;
            // an entry without title still needs something recognisable: fall back to its description
            String title = entry.title.isBlank() ? entry.description : entry.title;
            catalog.add(new GeminiClient.CatalogItem(index + 1, title, List.copyOf(entry.emotions), List.copyOf(keywords),
                    usageHint, entry.favorite));
        }
        return catalog;
    }
}
