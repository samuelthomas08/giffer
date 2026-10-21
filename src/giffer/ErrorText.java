package giffer;

import java.net.UnknownHostException;

/** Turns exceptions into a sentence a user can read. */
final class ErrorText {
    private ErrorText() {}

    static String of(Throwable failure) {
        if (failure instanceof UnknownHostException) return "Keine Verbindung zum Server (" + failure.getMessage() + ")";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
