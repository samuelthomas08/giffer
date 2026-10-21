package giffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Pastes whatever is on the clipboard into another running application: bring its window to the front,
 * then press Ctrl+V. Works with any app that accepts pasted files (chat clients, mail, editors, browsers).
 * Nothing is sent anywhere, the user still presses Enter in the target app. Windows only.
 */
final class PasteTarget {
    private PasteTarget() {}

    /** A visible top-level window of a running process. {@code processId == 0} marks a remembered app that is not running. */
    record WindowInfo(long processId, String processName, String windowTitle) {
        String applicationName() {
            return PasteTarget.applicationName(processName);
        }
    }

    /** What to paste into: a concrete process id, or a process name (plus optional title part) resolved at paste time. */
    record PasteRequest(long processId, String processName, String titleFilter) {}

    interface WindowLister {
        List<WindowInfo> listWindows() throws IOException, InterruptedException;
    }

    /** Replaceable so tests can show a made-up window list. */
    static WindowLister windowLister = PasteTarget::listWindowsViaPowerShell;

    private static final Map<String, String> FRIENDLY_APPLICATION_NAMES = Map.ofEntries(
            Map.entry("chrome", "Google Chrome"), Map.entry("msedge", "Microsoft Edge"), Map.entry("firefox", "Firefox"),
            Map.entry("ms-teams", "Microsoft Teams"), Map.entry("teams", "Microsoft Teams"), Map.entry("code", "VS Code"),
            Map.entry("idea64", "IntelliJ IDEA"), Map.entry("whatsapp", "WhatsApp"), Map.entry("slack", "Slack"),
            Map.entry("discord", "Discord"), Map.entry("telegram", "Telegram"), Map.entry("outlook", "Outlook"),
            Map.entry("olk", "Outlook"), Map.entry("notepad", "Notepad"), Map.entry("explorer", "Explorer"),
            Map.entry("signal", "Signal"), Map.entry("thunderbird", "Thunderbird"), Map.entry("brave", "Brave"),
            Map.entry("opera", "Opera"), Map.entry("vivaldi", "Vivaldi"));

    /** "msedge" becomes "Microsoft Edge", "Mattermost" stays, unknown names get a capital first letter. */
    static String applicationName(String processName) {
        if (processName == null || processName.isBlank()) return "";
        String friendlyName = FRIENDLY_APPLICATION_NAMES.get(processName.toLowerCase(Locale.ROOT));
        if (friendlyName != null) return friendlyName;
        return Character.toUpperCase(processName.charAt(0)) + processName.substring(1);
    }

    static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---------------------------------------------------------------- scripts
    // Windows PowerShell 5.1 ships with every Windows. Everything the user chose comes in through environment
    // variables and the script is passed as -EncodedCommand, so no user text is ever spliced into script text.

    private static final String LIST_WINDOWS_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            $ownProcessId = [int]$env:GIFFER_OWN_PID
            $ignoredProcesses = 'TextInputHost','PowerToys.QuickAccess','SearchHost','StartMenuExperienceHost','ShellExperienceHost','LockApp','ApplicationFrameHost'
            function ToBase64($text) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text)) }
            Get-Process | Where-Object {
                $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -and $_.Id -ne $ownProcessId -and $ignoredProcesses -notcontains $_.ProcessName
            } | Sort-Object ProcessName, MainWindowTitle | ForEach-Object {
                Write-Output ('W ' + $_.Id + ' ' + (ToBase64 $_.ProcessName) + ' ' + (ToBase64 $_.MainWindowTitle))
            }
            """;

    private static final String PASTE_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            $ownProcessId = [int]$env:GIFFER_OWN_PID
            $wantedProcessId = [int]$env:GIFFER_PROCESS_ID
            $wantedProcessName = $env:GIFFER_PROCESS_NAME
            $wantedTitlePart = $env:GIFFER_TITLE_FILTER
            $windows = @(Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.Id -ne $ownProcessId })
            if ($wantedProcessId -gt 0) {
                $candidateWindows = @($windows | Where-Object { $_.Id -eq $wantedProcessId })
            } else {
                $candidateWindows = $windows
                if ($wantedProcessName) {
                    $exactNameMatches = @($candidateWindows | Where-Object { $_.ProcessName -eq $wantedProcessName })
                    if ($exactNameMatches.Count -gt 0) { $candidateWindows = $exactNameMatches }
                    else { $candidateWindows = @($candidateWindows | Where-Object { $_.ProcessName.IndexOf($wantedProcessName, [StringComparison]::OrdinalIgnoreCase) -ge 0 }) }
                }
                if ($wantedTitlePart) { $candidateWindows = @($candidateWindows | Where-Object { $_.MainWindowTitle.IndexOf($wantedTitlePart, [StringComparison]::OrdinalIgnoreCase) -ge 0 }) }
            }
            if ($candidateWindows.Count -eq 0) { Write-Output 'NOTFOUND'; exit 3 }
            $target = $candidateWindows | Select-Object -First 1
            if ($env:GIFFER_DRY_RUN -eq '1') { Write-Output ('DRY ' + $target.Id + ' ' + $target.ProcessName); exit 0 }
            $shell = New-Object -ComObject WScript.Shell
            if (-not $shell.AppActivate([int]$target.Id)) { Write-Output 'NOFOCUS'; exit 4 }
            Start-Sleep -Milliseconds 500
            $shell.SendKeys('^v')
            Write-Output ('OK ' + $target.ProcessName)
            """;

    private static final int EXIT_NOT_FOUND = 3;
    private static final int EXIT_NO_FOCUS = 4;

    // ---------------------------------------------------------------- API

    /** Visible windows of other applications (blocking, about half a second): call off the UI thread. */
    static List<WindowInfo> listWindows() throws IOException, InterruptedException {
        return windowLister.listWindows();
    }

    private static List<WindowInfo> listWindowsViaPowerShell() throws IOException, InterruptedException {
        requireWindows();
        ScriptResult result = runPowerShell(LIST_WINDOWS_SCRIPT, Map.of());
        if (result.exitCode != 0) throw new IOException("Fensterliste nicht lesbar" + detailSuffix(result.output));

        List<WindowInfo> windows = new ArrayList<>();
        for (String line : result.output.split("\\R")) {
            String[] fields = line.strip().split(" ");
            if (fields.length != 4 || !fields[0].equals("W")) continue;
            try {
                windows.add(new WindowInfo(Long.parseLong(fields[1]), decodeBase64(fields[2]), decodeBase64(fields[3])));
            } catch (IllegalArgumentException malformedLine) {
                // skip the line
            }
        }
        return windows;
    }

    /** Blocking (about a second): call off the UI thread. Returns the name of the process that received Ctrl+V. */
    static String paste(PasteRequest request) throws IOException, InterruptedException {
        requireWindows();
        boolean hasProcessName = request.processName() != null && !request.processName().isBlank();
        if (request.processId() <= 0 && !hasProcessName) throw new IOException("Keine Ziel-Anwendung gewählt.");

        Map<String, String> environment = new HashMap<>();
        environment.put("GIFFER_PROCESS_ID", Long.toString(Math.max(0, request.processId())));
        environment.put("GIFFER_PROCESS_NAME", request.processName() == null ? "" : request.processName().strip());
        environment.put("GIFFER_TITLE_FILTER", request.titleFilter() == null ? "" : request.titleFilter().strip());
        if ("1".equals(System.getProperty("giffer.pasteDryRun"))) environment.put("GIFFER_DRY_RUN", "1"); // test hook: pick, don't paste
        ScriptResult result = runPowerShell(PASTE_SCRIPT, environment);

        switch (result.exitCode) {
            case 0:
                for (String line : result.output.split("\\R")) {
                    if (line.startsWith("OK ") || line.startsWith("DRY ")) return line.substring(line.indexOf(' ') + 1).strip();
                }
                return result.output;
            case EXIT_NOT_FOUND:
                String missingWindow = request.processId() > 0
                        ? "Das gewählte Fenster ist nicht mehr da"
                        : "Kein Fenster von „" + applicationName(request.processName()) + "“ gefunden";
                throw new IOException(missingWindow + ". Ist die Anwendung geöffnet (bei Browsern: der richtige Tab aktiv)?");
            case EXIT_NO_FOCUS:
                throw new IOException("Das Fenster ließ sich nicht in den Vordergrund holen.");
            default:
                throw new IOException("PowerShell-Fehler (Code " + result.exitCode + ")" + detailSuffix(result.output));
        }
    }

    // ---------------------------------------------------------------- helpers

    private record ScriptResult(int exitCode, String output) {}

    private static void requireWindows() throws IOException {
        if (!isSupported()) throw new IOException("Das Einfügen in andere Anwendungen funktioniert nur unter Windows.");
    }

    private static ScriptResult runPowerShell(String script, Map<String, String> environment) throws IOException, InterruptedException {
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript);
        processBuilder.environment().putAll(environment);
        processBuilder.environment().put("GIFFER_OWN_PID", Long.toString(ProcessHandle.current().pid()));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        process.getOutputStream().close();
        byte[] outputBytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("PowerShell hat nicht rechtzeitig geantwortet.");
        }
        return new ScriptResult(process.exitValue(), new String(outputBytes, StandardCharsets.UTF_8).strip());
    }

    private static String decodeBase64(String base64Text) {
        return new String(Base64.getDecoder().decode(base64Text), StandardCharsets.UTF_8);
    }

    private static String detailSuffix(String output) {
        if (output.isEmpty()) return "";
        return ": " + (output.length() > 200 ? output.substring(0, 200) + "…" : output);
    }
}
