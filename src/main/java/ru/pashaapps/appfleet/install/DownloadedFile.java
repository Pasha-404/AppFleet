package ru.pashaapps.appfleet.install;

import java.net.URI;
import java.nio.file.Path;

public record DownloadedFile(Path path, long size, URI originalUri) { }

