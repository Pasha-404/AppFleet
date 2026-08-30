package ru.pashaapps.appfleet.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** A durable property-based choice, intentionally not an exact asset filename. */
public record AssetSelectionRule(PackageType packageType, Architecture architecture, Set<String> requiredTokens) {
    public AssetSelectionRule {
        requiredTokens = Set.copyOf(requiredTokens);
    }
    public static AssetSelectionRule from(ReleaseAsset asset) {
        Set<String> tokens = new LinkedHashSet<>();
        Arrays.stream(asset.name().toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 3 && !token.matches("v?\\d+"))
                .filter(token -> !Set.of("exe", "msi", "zip", "setup", "installer", "install", "x64", "amd64", "win", "windows").contains(token))
                .forEach(tokens::add);
        return new AssetSelectionRule(asset.packageType(), asset.architecture(), tokens);
    }
    public boolean matches(ReleaseAsset asset) {
        if (asset.packageType() != packageType || (architecture != Architecture.UNKNOWN && asset.architecture() != architecture)) return false;
        String name = asset.name().toLowerCase(Locale.ROOT);
        return requiredTokens.stream().allMatch(name::contains);
    }
}

