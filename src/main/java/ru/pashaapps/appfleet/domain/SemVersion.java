package ru.pashaapps.appfleet.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict SemVer 2.0 parser used whenever a release can be compared safely. */
public final class SemVersion implements Comparable<SemVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );

    private final long major;
    private final long minor;
    private final long patch;
    private final List<String> prerelease;

    private SemVersion(long major, long minor, long patch, List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = List.copyOf(prerelease);
    }

    public static Optional<SemVersion> tryParse(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = PATTERN.matcher(value.trim());
        if (!matcher.matches()) return Optional.empty();
        try {
            List<String> prerelease = matcher.group(4) == null ? List.of() : List.of(matcher.group(4).split("\\."));
            if (prerelease.stream().anyMatch(identifier -> identifier.matches("0\\d+"))) return Optional.empty();
            return Optional.of(new SemVersion(
                    Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3)), prerelease));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public static SemVersion parse(String value) {
        return tryParse(value).orElseThrow(() -> new IllegalArgumentException("Недопустимая SemVer-версия: " + value));
    }

    public String normalized() {
        return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + String.join(".", prerelease));
    }

    @Override
    public int compareTo(SemVersion other) {
        int numeric = Long.compare(major, other.major);
        if (numeric == 0) numeric = Long.compare(minor, other.minor);
        if (numeric == 0) numeric = Long.compare(patch, other.patch);
        if (numeric != 0) return numeric;
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;
        int size = Math.min(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < size; index++) {
            String left = prerelease.get(index);
            String right = other.prerelease.get(index);
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            int comparison;
            if (leftNumeric && rightNumeric) comparison = Long.compare(Long.parseLong(left), Long.parseLong(right));
            else if (leftNumeric) comparison = -1;
            else if (rightNumeric) comparison = 1;
            else comparison = left.compareTo(right);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    @Override public boolean equals(Object other) {
        return other instanceof SemVersion version && compareTo(version) == 0;
    }
    @Override public int hashCode() { return Objects.hash(major, minor, patch, prerelease); }
    @Override public String toString() { return normalized(); }
}

