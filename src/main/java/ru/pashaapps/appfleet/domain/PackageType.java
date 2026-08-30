package ru.pashaapps.appfleet.domain;

import java.util.Locale;

public enum PackageType {
    INNO("inno"), EXE("exe"), MSI("msi"), ZIP("zip");

    private final String manifestValue;
    PackageType(String manifestValue) { this.manifestValue = manifestValue; }
    public String manifestValue() { return manifestValue; }
    public static PackageType fromAssetName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".msi")) return MSI;
        if (normalized.endsWith(".zip")) return ZIP;
        if (normalized.endsWith(".exe")) return EXE;
        throw new IllegalArgumentException("Неподдерживаемый тип пакета: " + name);
    }
    public static PackageType fromManifest(String value) {
        for (PackageType type : values()) if (type.manifestValue.equals(value)) return type;
        throw new IllegalArgumentException("Недопустимый installer.type: " + value);
    }
}

