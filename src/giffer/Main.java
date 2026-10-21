package giffer;

import javafx.application.Application;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Launcher. A separate class (not extending Application) so that system properties can be set before JavaFX
 * starts; the extended window style used for the custom title bar is still a preview API in JavaFX 25.
 */
public final class Main {
    private Main() {}

    public static void main(String[] arguments) {
        TlsTrust.trustWindowsCertificateStoreToo();
        System.setProperty("javafx.enablePreview", "true");
        System.setProperty("javafx.suppressPreviewWarning", "true");
        Thread.setDefaultUncaughtExceptionHandler(Main::logUncaughtException);
        Application.launch(GifferApp.class, arguments);
    }

    /** Writes the stack trace to {@code error.log} in the library folder (the app has no console). */
    private static void logUncaughtException(Thread thread, Throwable failure) {
        failure.printStackTrace();
        try {
            Path libraryDirectory = GifferApp.libraryDirectory();
            Files.createDirectories(libraryDirectory);
            Path logFile = libraryDirectory.resolve("error.log");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                writer.println("--- " + LocalDateTime.now() + " [" + thread.getName() + "]");
                failure.printStackTrace(writer);
            }
        } catch (IOException logFailure) {
            // there is nowhere left to report this
        }
    }
}
