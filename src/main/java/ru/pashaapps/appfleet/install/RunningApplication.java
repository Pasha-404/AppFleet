package ru.pashaapps.appfleet.install;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Stable identity of one verified running executable, captured before a force-close decision. */
public record RunningApplication(long pid, Instant startedAt, Path executable) {
    public RunningApplication {
        if (pid <= 0) throw new IllegalArgumentException("pid должен быть положительным");
        Objects.requireNonNull(startedAt, "startedAt");
        executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    public String command() { return executable.toString(); }
}
