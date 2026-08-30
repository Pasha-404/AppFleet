package ru.pashaapps.appfleet.domain;

import java.util.Locale;

public enum Architecture {
    X64, X86, ARM, ARM64, UNKNOWN;
    public static Architecture detect(String filename) {
        String text = filename.toLowerCase(Locale.ROOT);
        if (text.matches(".*(?:arm64|aarch64).*")) return ARM64;
        if (text.matches(".*(?:^|[-_.])arm(?:[-_.]|$).*")) return ARM;
        if (text.matches(".*(?:x86|win32|i386|32-bit).*")) return X86;
        if (text.matches(".*(?:x64|amd64|win64|64-bit).*")) return X64;
        return UNKNOWN;
    }
}

