package ru.pashaapps.appfleet.domain;

import org.junit.jupiter.api.Test;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManifestValidatorTest {
    @Test void validatesTheSortItStandardManifest() throws IOException {
        GithubRelease release = fixtureRelease();
        AppFleetManifest manifest = new ManifestValidator(AppFleetObjectMapper.create()).validate(resource("fixtures/sortit-manifest-v1.5.0.json"), RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit"), release);
        assertEquals("SortIt-Setup-1.5.0-x64.exe", manifest.installer().assetName());
        assertEquals(PackageType.INNO, manifest.installer().type());
    }
    @Test void rejectsAManifestThatReferencesAnUnknownAsset() throws IOException {
        String invalid = new String(resource("fixtures/sortit-manifest-v1.5.0.json"), StandardCharsets.UTF_8).replace("SortIt-Setup-1.5.0-x64.exe\"", "Unknown.exe\"");
        assertThrows(IllegalArgumentException.class, () -> new ManifestValidator(AppFleetObjectMapper.create()).validate(invalid.getBytes(StandardCharsets.UTF_8), RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit"), fixtureRelease()));
    }
    private static byte[] resource(String name) throws IOException { try (var input = ManifestValidatorTest.class.getClassLoader().getResourceAsStream(name)) { return input.readAllBytes(); } }
    private static GithubRelease fixtureRelease() { return new GithubRelease(264734621, "v1.5.0", "SortIt", "", false, false, Instant.parse("2026-08-29T13:07:14Z"), URI.create("https://github.com/Pasha-404/sortit/releases/tag/v1.5.0"), List.of(new ReleaseAsset(535231686, "SortIt-Setup-1.5.0-x64.exe", 1, URI.create("https://github.com/Pasha-404/sortit/releases/download/v1.5.0/SortIt-Setup-1.5.0-x64.exe"), ""), new ReleaseAsset(535231685, "SortIt-Setup-1.5.0-x64.exe.sha256", 1, URI.create("https://github.com/Pasha-404/sortit/releases/download/v1.5.0/SortIt-Setup-1.5.0-x64.exe.sha256"), ""))); }
}
