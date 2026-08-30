package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.domain.PackageType;

public record OperationPreview(ApplicationSnapshot application, String currentVersion, String targetVersion, String assetName,
                               long assetSize, String releaseDescription, String sourceUrl, boolean sha256Available,
                               boolean thirdPartyInteractiveExeWarning, PackageType packageType) { }

