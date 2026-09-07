package ru.pashaapps.appfleet.domain;

import java.util.Locale;

public enum Architecture {
    X64, X86, ARM, ARM64, UNKNOWN;
    public static Architecture detect(String filename) {
        String text = filename.toLowerCase(Locale.ROOT);
        if (hasToken(text, "arm64") || hasToken(text, "aarch64")) return ARM64;
        if (hasToken(text, "arm")) return ARM;
        // x86_64 must be recognized before x86; it is a common x64 compound, not an x86 package.
        if (hasToken(text, "x86_64") || hasToken(text, "x86-64") || hasToken(text, "x64") || hasToken(text, "amd64") || hasToken(text, "win64") || hasToken(text, "64-bit")) return X64;
        if (hasToken(text, "x86") || hasToken(text, "win32") || hasToken(text, "i386") || hasToken(text, "32-bit")) return X86;
        return UNKNOWN;
    }

    private static boolean hasToken(String text, String token) {
        return text.matches(".*(?:^|[-_.])" + java.util.regex.Pattern.quote(token) + "(?:[-_.]|$).*");
    }
}
