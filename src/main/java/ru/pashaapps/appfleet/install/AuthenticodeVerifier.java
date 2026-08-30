package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Calls Windows' native verifier with a constant PowerShell program, never interpolated input. */
public final class AuthenticodeVerifier {
    private static final String SCRIPT = "$s=Get-AuthenticodeSignature -LiteralPath $args[0]; Write-Output $s.Status";
    public AuthenticodeStatus verify(Path executable) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return AuthenticodeStatus.UNAVAILABLE;
        Process process = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT, executable.toAbsolutePath().normalize().toString())).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Истекло время проверки цифровой подписи"); }
            String status = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return switch (status) { case "Valid" -> AuthenticodeStatus.VALID; case "NotSigned" -> AuthenticodeStatus.NOT_SIGNED; default -> AuthenticodeStatus.INVALID; };
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Проверка цифровой подписи прервана", interrupted); }
    }
}
