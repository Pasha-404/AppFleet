package ru.pashaapps.appfleet.domain;

import java.net.URI;
import java.util.Objects;

public record ReleaseAsset(long id, String name, long size, URI downloadUri, String contentType) {
    public ReleaseAsset {
        if (id <= 0 || name == null || name.isBlank() || size < 0 || downloadUri == null) {
            throw new IllegalArgumentException("Некорректные сведения о файле релиза");
        }
    }
    public Architecture architecture() { return Architecture.detect(name); }
    public PackageType packageType() { return PackageType.fromAssetName(name); }
}

