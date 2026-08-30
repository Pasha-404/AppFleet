package ru.pashaapps.appfleet.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative release asset selection. It never resolves equal candidates by source ordering. */
public final class AssetSelector {
    private static final Set<String> REJECTED = Set.of("source", "sources", "src", "symbols", "debug", "javadoc", "linux", "mac", "osx", "darwin", "arm", "aarch", "x86", "win32", "checksum", "sha256", "sha512", "signature", "sig", "asc");
    private static final Set<String> INSTALLER_MARKERS = Set.of("setup", "installer", "install");
    private static final Set<String> ZIP_MARKERS = Set.of("portable", "win", "windows", "x64", "amd64");

    public AssetSelection select(GithubRelease release, AssetSelectionRule rememberedRule) {
        List<ReleaseAsset> eligible = eligibleAssets(release);
        if (rememberedRule != null) {
            List<ReleaseAsset> remembered = eligible.stream().filter(rememberedRule::matches).toList();
            if (remembered.size() == 1) return new AssetSelection.Selected(remembered.getFirst(), false);
            if (remembered.size() > 1) return new AssetSelection.NeedsChoice(remembered);
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
        String name = asset.name().toLowerCase(Locale.ROOT);
        try { asset.packageType(); } catch (IllegalArgumentException unsupported) { return false; }
        if (asset.architecture() == Architecture.ARM || asset.architecture() == Architecture.ARM64 || asset.architecture() == Architecture.X86) return false;
        return REJECTED.stream().noneMatch(marker -> name.contains(marker));
    }

    private int priority(ReleaseAsset asset) {
        String name = asset.name().toLowerCase(Locale.ROOT);
        if (asset.packageType() == PackageType.EXE && asset.architecture() == Architecture.X64 && INSTALLER_MARKERS.stream().anyMatch(name::contains)) return 1;
        if (asset.packageType() == PackageType.MSI && asset.architecture() == Architecture.X64) return 2;
        if (asset.packageType() == PackageType.ZIP && asset.architecture() == Architecture.X64 && ZIP_MARKERS.stream().anyMatch(name::contains)) return 3;
        return 4;
    }
}
