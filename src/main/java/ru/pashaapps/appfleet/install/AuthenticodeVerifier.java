package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Calls Windows' verifier without ever placing the target filename in PowerShell command text. */
public final class AuthenticodeVerifier {
    static final String TARGET_PATH_ENVIRONMENT_VARIABLE = "APPFLEET_AUTHENTICODE_TARGET_PATH";
    private static final String SCRIPT = "$ErrorActionPreference='Stop'; $path=$env:APPFLEET_AUTHENTICODE_TARGET_PATH; if ([string]::IsNullOrWhiteSpace($path)) { throw 'Missing Authenticode target path' }; $signature=Get-AuthenticodeSignature -LiteralPath $path; if ($null -eq $signature.SignerCertificate) { [Console]::Out.WriteLine('NotSigned') } else { [Console]::Out.WriteLine($signature.Status.ToString()) }";
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;
    private static final long VERIFIER_TIMEOUT_SECONDS = 30;

    public AuthenticodeStatus verify(Path executable) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return AuthenticodeStatus.UNAVAILABLE;
        ProcessBuilder command = commandFor(executable);
        Process process = command.start();
        ExecutorService outputReader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> output = outputReader.submit(() -> readBoundedOutput(process.getInputStream()));
            if (!process.waitFor(VERIFIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Истекло время проверки цифровой подписи");
            }
            return statusFrom(process.exitValue(), awaitOutput(output));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Проверка цифровой подписи прервана", interrupted);
        } finally {
            outputReader.shutdownNow();
        }
    }

    static ProcessBuilder commandFor(Path executable) {
        ProcessBuilder command = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT));
        command.redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().remove("PSModulePath");
        command.environment().put(TARGET_PATH_ENVIRONMENT_VARIABLE, executable.toAbsolutePath().normalize().toString());
        return command;
    }

    static AuthenticodeStatus statusFrom(int exitCode, String output) throws IOException {
        if (exitCode != 0) throw new IOException("Средство проверки цифровой подписи завершилось с кодом " + exitCode);
        return switch (output.strip()) {
            case "Valid" -> AuthenticodeStatus.VALID;
            case "NotSigned" -> AuthenticodeStatus.NOT_SIGNED;
            case "HashMismatch", "NotTrusted", "NotSupportedFileFormat", "UnknownError" -> AuthenticodeStatus.INVALID;
            default -> throw new IOException("Средство проверки цифровой подписи вернуло неизвестный результат");
        };
    }

    private static String awaitOutput(Future<String> output) throws IOException, InterruptedException {
        try {
            return output.get(VERIFIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            throw new IOException("Не удалось прочитать результат проверки цифровой подписи", failure.getCause());
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("Средство проверки цифровой подписи не завершило вывод", timeout);
        }
    }

    private static String readBoundedOutput(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            boolean truncated = false;
            for (int read; (read = input.read(buffer)) != -1;) {
                int remaining = MAX_OUTPUT_BYTES - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
                truncated |= read > remaining;
            }
            if (truncated) throw new IOException("Средство проверки цифровой подписи вернуло слишком много данных");
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
