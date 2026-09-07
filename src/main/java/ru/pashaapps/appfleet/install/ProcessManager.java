package ru.pashaapps.appfleet.install;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds a process only by its verified executable and requests a normal Windows window close.
 * {@link ProcessHandle#destroy()} is intentionally never used as a graceful-close mechanism on Windows.
 */
public final class ProcessManager {
    public List<RunningApplication> findByVerifiedExecutable(Path expectedExecutable, Set<String> allowedNames) {
        if (expectedExecutable == null || !Files.isRegularFile(expectedExecutable)) return List.of();
        Path expected = expectedExecutable.toAbsolutePath().normalize();
        Set<String> expectedNames = allowedNames.isEmpty()
                ? Set.of(expected.getFileName().toString().toLowerCase(Locale.ROOT))
                : allowedNames.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        long currentPid = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.pid() != currentPid)
                .map(handle -> asVerifiedApplication(handle, expected, expectedNames).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public GracefulCloseResult requestGracefulClose(RunningApplication process, Duration timeout) {
        Match match = match(process);
        if (match == Match.NOT_RUNNING) return GracefulCloseResult.NOT_RUNNING;
        if (match != Match.SAME) return GracefulCloseResult.IDENTITY_CHANGED;
        if (!requestWindowClose(process.pid())) return GracefulCloseResult.NO_TOP_LEVEL_WINDOW;
        return awaitExit(process, timeout) ? GracefulCloseResult.CLOSED : GracefulCloseResult.STILL_RUNNING;
    }

    public ForceCloseResult forceCloseAfterExplicitConsent(RunningApplication process, Duration timeout) {
        Match match = match(process);
        if (match == Match.NOT_RUNNING) return ForceCloseResult.NOT_RUNNING;
        if (match != Match.SAME) return ForceCloseResult.IDENTITY_CHANGED;
        ProcessHandle handle = ProcessHandle.of(process.pid()).orElseThrow();
        handle.destroyForcibly();
        return awaitExit(process, timeout) ? ForceCloseResult.CLOSED : ForceCloseResult.STILL_RUNNING;
    }

    static boolean matchesVerifiedExecutable(Path candidate, Path expected, Set<String> allowedNames) {
        try {
            Path candidatePath = candidate.toAbsolutePath().normalize();
            String candidateName = candidatePath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!allowedNames.contains(candidateName)) return false;
            return Files.isSameFile(candidatePath, expected.toAbsolutePath().normalize());
        } catch (IOException | SecurityException invalidPath) {
            return false;
        }
    }

    private static Optional<RunningApplication> asVerifiedApplication(ProcessHandle handle, Path expected, Set<String> allowedNames) {
        Optional<String> command = handle.info().command();
        Optional<Instant> started = handle.info().startInstant();
        if (command.isEmpty() || started.isEmpty()) return Optional.empty();
        try {
            Path candidate = Path.of(command.get());
            return matchesVerifiedExecutable(candidate, expected, allowedNames)
                    ? Optional.of(new RunningApplication(handle.pid(), started.get(), expected))
                    : Optional.empty();
        } catch (RuntimeException malformedCommand) {
            return Optional.empty();
        }
    }

    private Match match(RunningApplication process) {
        Optional<ProcessHandle> handle = ProcessHandle.of(process.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) return Match.NOT_RUNNING;
        return asVerifiedApplication(handle.get(), process.executable(), Set.of(process.executable().getFileName().toString().toLowerCase(Locale.ROOT)))
                .filter(candidate -> candidate.startedAt().equals(process.startedAt()))
                .isPresent() ? Match.SAME : Match.IDENTITY_CHANGED;
    }

    private boolean awaitExit(RunningApplication process, Duration timeout) {
        Optional<ProcessHandle> handle = ProcessHandle.of(process.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) return true;
        try {
            handle.get().onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The final identity check below distinguishes a timeout from PID reuse.
        }
        return match(process) == Match.NOT_RUNNING;
    }

    private static boolean requestWindowClose(long pid) {
        AtomicBoolean matchedWindow = new AtomicBoolean();
        try {
            User32.INSTANCE.EnumWindows((window, ignored) -> {
                if (!User32.INSTANCE.IsWindowVisible(window)) return true;
                IntByReference ownerPid = new IntByReference();
                if (User32.INSTANCE.GetWindowThreadProcessId(window, ownerPid) == 0 || ownerPid.getValue() != pid) return true;
                matchedWindow.set(true);
                User32.INSTANCE.SendMessageTimeout(window, WinUser.WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0),
                        WinUser.SMTO_ABORTIFHUNG | WinUser.SMTO_ERRORONEXIT, 2_000, new WinDef.DWORDByReference());
                return true;
            }, Pointer.NULL);
        } catch (UnsatisfiedLinkError unavailable) {
            return false;
        }
        return matchedWindow.get();
    }

    private enum Match { SAME, NOT_RUNNING, IDENTITY_CHANGED }

    public enum GracefulCloseResult { CLOSED, STILL_RUNNING, NO_TOP_LEVEL_WINDOW, NOT_RUNNING, IDENTITY_CHANGED }
    public enum ForceCloseResult { CLOSED, STILL_RUNNING, NOT_RUNNING, IDENTITY_CHANGED }
}
