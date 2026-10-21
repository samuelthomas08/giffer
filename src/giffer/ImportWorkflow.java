package giffer;

import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Adds GIFs to the library from files, folders or web links, then reports what happened in one toast. */
final class ImportWorkflow {
    private final GifferApp app;

    ImportWorkflow(GifferApp app) {
        this.app = app;
    }

    /** What happened to one link or file. */
    private enum Outcome { ADDED, ALREADY_PRESENT, FAILED }

    private record ImportResult(Outcome outcome, GifEntry entry, String failureText) {}

    /** Counts the outcomes of everything imported in one go. */
    private static final class BatchSummary {
        final int expectedCount;
        int finishedCount;
        int addedCount;
        int alreadyPresentCount;
        int failedCount;
        String lastFailureText = "";
        GifEntry lastEntry;

        BatchSummary(int expectedCount) {
            this.expectedCount = expectedCount;
        }

        /** The one entry to show if the whole batch was about a single GIF. */
        GifEntry singleEntryOrNull() {
            return addedCount + alreadyPresentCount == 1 ? lastEntry : null;
        }
    }

    // ---------------------------------------------------------------- files and folders

    /** Adds the given GIF files; folders are searched recursively for *.gif. */
    void importFiles(List<File> filesAndFolders) {
        app.activity.changePendingImports(1);
        app.backgroundThreads.submit(() -> {
            List<File> gifFiles = new ArrayList<>();
            for (File fileOrFolder : filesAndFolders) collectGifFiles(fileOrFolder, gifFiles);

            BatchSummary summary = new BatchSummary(gifFiles.size());
            for (File gifFile : gifFiles) {
                ImportResult result;
                try {
                    GifLibrary.AddResult added = app.library.addFile(gifFile);
                    result = new ImportResult(added.created() ? Outcome.ADDED : Outcome.ALREADY_PRESENT, added.entry(), "");
                } catch (IOException failure) {
                    result = new ImportResult(Outcome.FAILED, null, failure.getMessage());
                }
                ImportResult finishedResult = result;
                Platform.runLater(() -> record(summary, finishedResult));
            }
            Platform.runLater(() -> {
                app.activity.changePendingImports(-1);
                showSummary(summary);
            });
        });
    }

    private static void collectGifFiles(File fileOrFolder, List<File> gifFiles) {
        if (fileOrFolder.isDirectory()) {
            try (Stream<Path> paths = Files.walk(fileOrFolder.toPath())) {
                paths.filter(ImportWorkflow::hasGifExtension).forEach(path -> gifFiles.add(path.toFile()));
            } catch (IOException unreadableFolder) {
                // skip folders we cannot read
            }
        } else if (hasGifExtension(fileOrFolder.toPath())) {
            gifFiles.add(fileOrFolder);
        }
    }

    private static boolean hasGifExtension(Path path) {
        return path.toString().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    // ---------------------------------------------------------------- web links

    /** Downloads the GIF behind each link (Tenor, Giphy, direct links, ...) and adds it. */
    void importLinks(List<String> links) {
        List<String> uniqueLinks = links.stream().map(String::strip).filter(link -> !link.isEmpty()).distinct().toList();
        if (uniqueLinks.isEmpty()) return;
        BatchSummary summary = new BatchSummary(uniqueLinks.size());
        app.activity.changePendingImports(uniqueLinks.size());
        for (String link : uniqueLinks) {
            app.backgroundThreads.submit(() -> {
                ImportResult result = importOneLink(link);
                Platform.runLater(() -> {
                    app.activity.changePendingImports(-1);
                    record(summary, result);
                    if (summary.finishedCount == summary.expectedCount) showSummary(summary);
                });
            });
        }
    }

    private ImportResult importOneLink(String link) {
        try {
            String normalizedLink = LinkImporter.normalizeUrl(link);
            GifEntry knownEntry = app.library.findBySourceUrl(normalizedLink);
            if (knownEntry != null) return new ImportResult(Outcome.ALREADY_PRESENT, knownEntry, "");

            LinkImporter.ImportedGif downloaded = LinkImporter.fetch(normalizedLink);
            GifLibrary.AddResult added = app.library.add(
                    downloaded.gifBytes(), downloaded.suggestedFileName(), downloaded.sourceUrl(), downloaded.sourceHint());
            return new ImportResult(added.created() ? Outcome.ADDED : Outcome.ALREADY_PRESENT, added.entry(), "");
        } catch (Throwable failure) {
            return new ImportResult(Outcome.FAILED, null, ErrorText.of(failure));
        }
    }

    // ---------------------------------------------------------------- results

    private void record(BatchSummary summary, ImportResult result) {
        summary.finishedCount++;
        switch (result.outcome()) {
            case ADDED -> {
                summary.addedCount++;
                summary.lastEntry = result.entry();
                app.refreshResultsSoon();
                if (app.settings.analyzeNewGifsAutomatically && app.settings.hasGeminiApiKey()) app.analysis.analyze(result.entry());
            }
            case ALREADY_PRESENT -> {
                summary.alreadyPresentCount++;
                summary.lastEntry = result.entry();
            }
            case FAILED -> {
                summary.failedCount++;
                summary.lastFailureText = result.failureText();
            }
        }
    }

    private void showSummary(BatchSummary summary) {
        int total = summary.addedCount + summary.alreadyPresentCount + summary.failedCount;
        if (total == 0) {
            app.toasts.show("Keine GIFs gefunden", ToastLayer.Kind.INFO);
            return;
        }
        if (summary.addedCount + summary.alreadyPresentCount == 0) {
            String message = summary.failedCount == 1
                    ? summary.lastFailureText
                    : summary.failedCount + " Links konnten nicht geladen werden – " + summary.lastFailureText;
            app.toasts.show(message, ToastLayer.Kind.ERROR);
            return;
        }

        List<String> parts = new ArrayList<>();
        if (summary.addedCount > 0) {
            parts.add(summary.addedCount == 1 ? "GIF hinzugefügt" : summary.addedCount + " GIFs hinzugefügt");
        }
        if (summary.alreadyPresentCount > 0) {
            parts.add(summary.alreadyPresentCount == 1 ? "1 GIF war schon vorhanden" : summary.alreadyPresentCount + " GIFs waren schon vorhanden");
        }
        if (summary.failedCount > 0) parts.add(summary.failedCount + " fehlgeschlagen");
        String message = String.join(" · ", parts);
        if (summary.addedCount > 0 && app.settings.analyzeNewGifsAutomatically && app.settings.hasGeminiApiKey()) {
            message += " – Gemini analysiert …";
        } else if (summary.addedCount > 0 && !app.settings.hasGeminiApiKey()) {
            message += " (für die Analyse fehlt der API-Key)";
        }
        boolean isPlainSuccess = summary.failedCount == 0 && summary.addedCount > 0;
        app.toasts.show(message, isPlainSuccess ? ToastLayer.Kind.SUCCESS : ToastLayer.Kind.INFO);

        GifEntry singleEntry = summary.singleEntryOrNull();
        if (singleEntry != null) app.showEntryInResults(singleEntry);
    }
}
