package ru.pashaapps.appfleet.domain;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AssetSelectorTest {
    private final AssetSelector selector = new AssetSelector();
    @Test void selectsMarkedX64InstallerBeforeMsiAndZip() {
        GithubRelease release = release(asset("Tool.zip"), asset("Tool-x64.msi"), asset("Tool-Setup-x64.exe"));
        AssetSelection.Selected selection = assertInstanceOf(AssetSelection.Selected.class, selector.select(release, null));
        assertEquals("Tool-Setup-x64.exe", selection.asset().name());
    }
    @Test void refusesToPickTheFirstOfEqualCandidates() {
        GithubRelease release = release(asset("Tool-x64.msi"), asset("Tool-win64.msi"));
        assertInstanceOf(AssetSelection.NeedsChoice.class, selector.select(release, null));
    }
    @Test void excludesSourceAndUnsupportedArchitectures() {
        GithubRelease release = release(asset("Tool-sources.zip"), asset("Tool-arm64.exe"), asset("Tool-x64.zip"));
        AssetSelection.Selected selection = assertInstanceOf(AssetSelection.Selected.class, selector.select(release, null));
        assertEquals("Tool-x64.zip", selection.asset().name());
    }
    @Test void acceptsX8664AndProductNamesThatContainSignatureFragments() {
        GithubRelease release = release(asset("DesignTool-windows-x86_64.zip"), asset("DesignTool-x64.exe"));

        assertEquals(Architecture.X64, Architecture.detect("DesignTool-windows-x86_64.zip"));
        assertTrue(selector.eligibleAssets(release).stream().map(ReleaseAsset::name).toList().containsAll(List.of("DesignTool-windows-x86_64.zip", "DesignTool-x64.exe")));
    }
    @Test void exactChoiceAppliesOnlyToTheReleaseWhereItWasMade() {
        ReleaseAsset setup = asset("Widget-Setup-1.0.0-x64.exe");
        ReleaseAsset installer = asset("Widget-Installer-1.0.0-x64.exe");
        GithubRelease first = release(setup, installer);
        AssetSelectionRule rule = AssetSelectionRule.from(installer);

        AssetSelection.Selected current = assertInstanceOf(AssetSelection.Selected.class, selector.select(first, rule, first.id(), installer.id()));
        assertEquals(installer.id(), current.asset().id());

        GithubRelease next = new GithubRelease(2, "v1.1.0", "", "", false, false, Instant.EPOCH, URI.create("https://github.com/a/b/releases/tag/v1.1.0"), List.of(asset("Widget-Setup-1.1.0-x64.exe"), asset("Widget-Installer-1.1.0-x64.exe")));
        assertInstanceOf(AssetSelection.NeedsChoice.class, selector.select(next, rule, first.id(), installer.id()));
    }
    @Test void rememberedRuleWithNoFutureMatchesRequiresChoiceInsteadOfFallback() {
        ReleaseAsset chosen = asset("Widget-Setup-1.0.0-x64.exe");
        GithubRelease first = release(chosen);
        AssetSelectionRule rule = AssetSelectionRule.from(chosen);
        GithubRelease next = new GithubRelease(2, "v1.1.0", "", "", false, false, Instant.EPOCH, URI.create("https://github.com/a/b/releases/tag/v1.1.0"), List.of(asset("Other-x64.msi")));

        assertInstanceOf(AssetSelection.NeedsChoice.class, selector.select(next, rule, first.id(), chosen.id()));
    }
    private static GithubRelease release(ReleaseAsset... assets) { return new GithubRelease(1, "v1.0.0", "", "", false, false, Instant.EPOCH, URI.create("https://github.com/a/b/releases/tag/v1.0.0"), List.of(assets)); }
    private static ReleaseAsset asset(String name) { return new ReleaseAsset(1 + name.hashCode() & 0x7fff_ffffL, name, 1, URI.create("https://github.com/a/b/releases/download/v1.0.0/" + name), ""); }
}
