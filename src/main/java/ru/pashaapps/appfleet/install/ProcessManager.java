package ru.pashaapps.appfleet.install;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Matches only known exact executable names and offers graceful then explicitly forced closure. */
public final class ProcessManager {
    public List<RunningApplication> findByExactNames(Set<String> allowedNames) {
        Set<String> expected = allowedNames.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return ProcessHandle.allProcesses().filter(handle -> handle.info().command().isPresent()).map(handle -> {
            String command = handle.info().command().orElseThrow();
            String filename;
            try { filename = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT); } catch (Exception malformed) { filename = ""; }
            return expected.contains(filename) ? new RunningApplication(handle.pid(), command) : null;
        }).filter(java.util.Objects::nonNull).toList();
    }
    public boolean requestGracefulClose(RunningApplication process, Duration timeout) {
        return ProcessHandle.of(process.pid()).map(handle -> {
            if (!handle.destroy()) return !handle.isAlive();
            try { return handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS) != null; }
            catch (Exception timedOut) { return !handle.isAlive(); }
        }).orElse(true);
    }
    public boolean forceCloseAfterExplicitConsent(RunningApplication process, Duration timeout) {
        return ProcessHandle.of(process.pid()).map(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
            try { return handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS) != null; }
            catch (Exception timedOut) { return !handle.isAlive(); }
        }).orElse(true);
    }
}

