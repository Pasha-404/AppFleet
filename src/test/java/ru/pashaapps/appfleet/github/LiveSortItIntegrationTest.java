package ru.pashaapps.appfleet.github;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.GithubRelease;
import ru.pashaapps.appfleet.domain.ManifestValidator;
import ru.pashaapps.appfleet.domain.RepositoryId;

import java.net.http.HttpClient;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real GitHub integration using the repository required by the specification. */
@Tag("live")
@EnabledIfSystemProperty(named = "runLiveTests", matches = "true")
class LiveSortItIntegrationTest {
    @Test void recognizesThePublishedSortItV150StandardRelease() throws Exception {
        RepositoryId repository = RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit/releases/tag/v1.5.0");
        GithubApiClient client = new GithubApiClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), AppFleetObjectMapper.create(), "test");
        GithubRelease release = client.getReleaseByTag(repository, "v1.5.0");
        assertTrue(release.isStable());
        assertEquals("v1.5.0", release.tagName());
        var manifestAsset = release.assets().stream().filter(asset -> asset.name().equals("appfleet-manifest.json")).findFirst().orElseThrow();
        var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(java.net.http.HttpRequest.newBuilder(manifestAsset.downloadUri())
                .header("User-Agent", "AppFleet-test/1.0.0").header("Accept", "application/octet-stream").build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        byte[] manifestContents = response.body();
        AppFleetManifest manifest = new ManifestValidator(AppFleetObjectMapper.create()).validate(manifestContents, repository, release);
        assertEquals("SortIt-Setup-1.5.0-x64.exe", manifest.installer().assetName());
        assertNotNull(manifest.appId());
    }
}
