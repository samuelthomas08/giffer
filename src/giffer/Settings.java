package giffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** User preferences, stored as a small properties file next to the library. */
final class Settings {
    static final String DEFAULT_GEMINI_MODEL = GeminiClient.MODELS.get(0);

    /** Chooses between following Windows ("system") and forcing "light" or "dark". */
    static final String THEME_SYSTEM = "system";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";

    String geminiApiKey = "";
    String geminiModel = DEFAULT_GEMINI_MODEL;
    boolean analyzeNewGifsAutomatically = true;
    String themeMode = THEME_SYSTEM;

    /** Default application to paste GIFs into, as a process name such as "Mattermost" (empty = none chosen yet). */
    String pasteProcessName = "";
    /** Optional: only windows whose title contains this text count (tells browser windows apart). */
    String pasteWindowTitleFilter = "";
    /** Show the application picker before every paste instead of pasting straight into the default. */
    boolean askBeforePasting = false;

    /** The key from the settings, falling back to the GEMINI_API_KEY / GOOGLE_API_KEY environment variables. */
    String effectiveGeminiApiKey() {
        if (geminiApiKey != null && !geminiApiKey.isBlank()) return geminiApiKey.strip();
        for (String variableName : new String[]{"GEMINI_API_KEY", "GOOGLE_API_KEY"}) {
            String value = System.getenv(variableName);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    boolean hasGeminiApiKey() {
        return !effectiveGeminiApiKey().isEmpty();
    }

    Settings copy() {
        Settings copy = new Settings();
        copy.geminiApiKey = geminiApiKey;
        copy.geminiModel = geminiModel;
        copy.analyzeNewGifsAutomatically = analyzeNewGifsAutomatically;
        copy.themeMode = themeMode;
        copy.pasteProcessName = pasteProcessName;
        copy.pasteWindowTitleFilter = pasteWindowTitleFilter;
        copy.askBeforePasting = askBeforePasting;
        return copy;
    }

    /** A missing or unreadable file simply yields the defaults. */
    static Settings load(Path file) {
        Settings settings = new Settings();
        if (!Files.exists(file)) return settings;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ignored) {
            // fall through with whatever was read
        }
        settings.geminiApiKey = properties.getProperty("geminiApiKey", "");
        settings.geminiModel = properties.getProperty("geminiModel", DEFAULT_GEMINI_MODEL);
        settings.analyzeNewGifsAutomatically = Boolean.parseBoolean(properties.getProperty("autoAnalyze", "true"));
        settings.themeMode = properties.getProperty("theme", THEME_SYSTEM);
        settings.pasteProcessName = properties.getProperty("pasteProcess", "");
        settings.pasteWindowTitleFilter = properties.getProperty("pasteTitle", "");
        settings.askBeforePasting = Boolean.parseBoolean(properties.getProperty("askBeforePaste", "false"));
        return settings;
    }

    void save(Path file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("geminiApiKey", geminiApiKey == null ? "" : geminiApiKey);
        properties.setProperty("geminiModel", geminiModel);
        properties.setProperty("autoAnalyze", Boolean.toString(analyzeNewGifsAutomatically));
        properties.setProperty("theme", themeMode);
        properties.setProperty("pasteProcess", pasteProcessName);
        properties.setProperty("pasteTitle", pasteWindowTitleFilter);
        properties.setProperty("askBeforePaste", Boolean.toString(askBeforePasting));
        Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Giffer settings");
        }
    }
}
