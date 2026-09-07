package ru.pashaapps.appfleet.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative release asset selection. Equal candidates always require an explicit choice. */
public final class AssetSelector {
    private static final Set<String> REJECTED_TOKENS = Set.of("source", "sources", "src", "symbols", "debug", "javadoc", "linux", "mac", "osx", "darwin");
    private static final Set<String> INSTALLER_MARKERS = Set.of("setup", "installer", "install");
    private static final Set<String> ZIP_MARKERS = Set.of("portable", "win", "windows", "x64", "amd64");

    public AssetSelection select(GithubRelease release, AssetSelectionRule rememberedRule) {
        return select(release, rememberedRule, null, null);
    }

    /** Exact choice is limited to the release where the user made it; later releases use the durable rule. */
    public AssetSelection select(GithubRelease release, AssetSelectionRule rememberedRule, Long exactReleaseId, Long exactAssetId) {
        List<ReleaseAsset> eligible = eligibleAssets(release);
        if (exactReleaseId != null && exactAssetId != null && exactReleaseId == release.id()) {
            return eligible.stream().filter(asset -> asset.id() == exactAssetId).findFirst()
                    .<AssetSelection>map(asset -> new AssetSelection.Selected(asset, false))
                    .orElseGet(() -> new AssetSelection.NeedsChoice(eligible));
        }
        if (rememberedRule != null) {
            List<ReleaseAsset> remembered = eligible.stream().filter(rememberedRule::matches).toList();
            if (remembered.size() == 1) return new AssetSelection.Selected(remembered.getFirst(), false);
            // Zero matches is not permission to silently use another package family.
            return new AssetSelection.NeedsChoice(remembered.isEmpty() ? eligible : remembered);
        }
        if (eligible.isEmpty()) return new AssetSelection.None("Не найден подходящий Windows x64 файл релиза");
        int bestPriority = eligible.stream().mapToInt(this::priority).min().orElseThrow();
        List<ReleaseAsset> best = eligible.stream().filter(asset -> priority(asset) == bestPriority).toList();
        return best.size() == 1 ? new AssetSelection.Selected(best.getFirst(), false) : new AssetSelection.NeedsChoice(best);
    }

    /** Candidates that may safely be offered for an explicit user selection. */
    public List<ReleaseAsset> eligibleAssets(GithubRelease release) {
        return release.assets().stream().filter(this::isEligible).toList();
    }

    private boolean isEligible(ReleaseAsset asset) {
        try { asset.packageType(); } catch (IllegalArgumentException unsupported) { return false; }
        if (asset.architecture() == Architecture.ARM || asset.architecture() == Architecture.ARM64 || asset.architecture() == Architecture.X86) return false;
        return tokens(asset.name()).stream().noneMatch(REJECTED_TOKENS::contains);
    }

    private int priority(ReleaseAsset asset) {
        Set<String> tokens = tokens(asset.name());
        if (asset.packageType() == PackageType.EXE && asset.architecture() == Architecture.X64 && tokens.stream().anyMatch(INSTALLER_MARKERS::contains)) return 1;
        if (asset.packageType() == PackageType.MSI && asset.architecture() == Architecture.X64) return 2;
        if (asset.packageType() == PackageType.ZIP && asset.architecture() == Architecture.X64 && tokens.stream().anyMatch(ZIP_MARKERS::contains)) return 3;
        return 4;
    }

    private static Set<String> tokens(String filename) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : filename.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!part.isBlank()) tokens.add(part);
        return Set.copyOf(tokens);
    }
}
