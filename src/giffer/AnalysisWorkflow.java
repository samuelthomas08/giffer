package giffer;

import javafx.application.Platform;

import java.io.File;

/** Sends GIFs to Gemini in the background and stores what comes back. All state changes happen on the UI thread. */
final class AnalysisWorkflow {
    private final GifferApp app;

    AnalysisWorkflow(GifferApp app) {
        this.app = app;
    }

    /** Analyses one GIF (again, if it was analysed before). Does nothing while that GIF is already being analysed. */
    void analyze(GifEntry entry) {
        if (!app.settings.hasGeminiApiKey()) {
            app.toasts.show("Kein Gemini API-Key hinterlegt – bitte in den Einstellungen eintragen.", ToastLayer.Kind.ERROR);
            return;
        }
        if (entry.analysisStatus == GifEntry.AnalysisStatus.ANALYZING) return;
        entry.analysisStatus = GifEntry.AnalysisStatus.ANALYZING;
        entry.analysisError = "";
        app.activity.changePendingAnalyses(1);
        app.onAnalysisStateChanged(entry);

        Settings settingsSnapshot = app.settings.copy(); // the user may edit the settings while the request runs
        File gifFile = app.library.gifFile(entry);
        String sourceHint = entry.sourceHint;
        app.analysisThreads.submit(() -> {
            GeminiClient.Analysis analysis = null;
            String failureText = null;
            try {
                analysis = GeminiClient.analyze(gifFile, settingsSnapshot, sourceHint);
            } catch (Throwable failure) {
                failureText = ErrorText.of(failure);
            }
            GeminiClient.Analysis finishedAnalysis = analysis;
            String finishedFailureText = failureText;
            Platform.runLater(() -> storeResult(entry, finishedAnalysis, finishedFailureText));
        });
    }

    private void storeResult(GifEntry entry, GeminiClient.Analysis analysis, String failureText) {
        app.activity.changePendingAnalyses(-1);
        if (analysis != null) {
            entry.title = analysis.title();
            entry.description = analysis.description();
            entry.emotions = analysis.emotions();
            entry.keywords = analysis.keywords();
            entry.usageHint = analysis.usageHint();
            entry.textInGif = analysis.textInGif();
            entry.analysisStatus = GifEntry.AnalysisStatus.DONE;
            entry.analysisError = "";
        } else {
            entry.analysisStatus = GifEntry.AnalysisStatus.ERROR;
            entry.analysisError = failureText;
        }
        app.library.save();
        app.onAnalysisStateChanged(entry);
        app.refreshResultsSoon();
    }

    /** Analyses everything that was never analysed or failed before. */
    void analyzeAllPending() {
        for (GifEntry entry : app.library.entriesNeedingAnalysis()) analyze(entry);
    }

    /** Same, for the button in the UI: explains first if there is no API key yet. */
    void analyzeAllPendingOnUserRequest() {
        if (!app.settings.hasGeminiApiKey()) {
            app.toasts.show("Bitte zuerst einen Gemini API-Key in den Einstellungen eintragen.", ToastLayer.Kind.ERROR);
            return;
        }
        analyzeAllPending();
    }

    boolean isBusy() {
        return app.activity.pendingAnalyses() > 0;
    }
}
