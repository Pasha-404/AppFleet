package ru.pashaapps.appfleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.pashaapps.appfleet.BuildInfo;
import ru.pashaapps.appfleet.domain.*;
import ru.pashaapps.appfleet.github.GithubApiClient;
import ru.pashaapps.appfleet.install.*;
import ru.pashaapps.appfleet.persistence.AppPaths;
import ru.pashaapps.appfleet.persistence.AtomicJsonStore;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** External Inno Setup self-update: verified first, launched without waiting, then the app exits. */
public final class SelfUpdateService {
    private static final Logger log = LoggerFactory.getLogger(SelfUpdateService.class);
    private final BuildInfo build;
    private final AppPaths paths;
    private final GithubApiClient github;
    private final GithubAssetDownloader downloader;
    private final ManifestValidator manifests;
    private final AtomicJsonStore<SelfUpdateMarker> markerStore;
    public SelfUpdateService(BuildInfo build, AppPaths paths, ObjectMapper mapper) {
        this.build = build;
        this.paths = paths;
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.github = new GithubApiClient(http, mapper, build.version());
        this.downloader = new GithubAssetDownloader(http, "AppFleet/" + build.version());
        this.manifests = new ManifestValidator(mapper);
        this.markerStore = new AtomicJsonStore<>(mapper, SelfUpdateMarker.class, paths.cacheDirectory().resolve("self-update.json"));
    }
    public void recoverAfterLaunch() {
        markerStore.read().ifPresent(marker -> {
            if (SemVersion.tryParse(build.version()).isPresent() && SemVersion.parse(build.version()).normalized().equals(SemVersion.parse(marker.targetVersion()).normalized())) {
                deleteDirectory(Path.of(marker.operationDirectory()));
                try { Files.deleteIfExists(paths.cacheDirectory().resolve("self-update.json")); } catch (IOException ignored) { }
                log.info("Самообновление до {} подтверждено запуском новой версии", marker.targetVersion());
            } else log.error("Предыдущее самообновление до {} не подтвердилось; повторный запуск в этом сеансе заблокирован", marker.targetVersion());
        });
    }
    public CompletableFuture<Optional<SelfUpdateOffer>> checkAsync(Executor executor) { return CompletableFuture.supplyAsync(this::check, executor); }
    public Optional<SelfUpdateOffer> check() {
        RepositoryId repository = RepositoryId.fromGithubUrl(build.repositoryUrl());
        GithubRelease release = github.listReleases(repository, null).body().stream().filter(GithubRelease::isStable).max(Comparator.comparing(GithubRelease::publishedAt)).orElse(null);
        if (release == null || SemVersion.tryParse(release.tagName()).isEmpty() || SemVersion.tryParse(build.version()).isEmpty() || SemVersion.parse(release.tagName()).compareTo(SemVersion.parse(build.version())) <= 0) return Optional.empty();
        AppFleetManifest manifest = readManifest(repository, release);
        if (!manifest.appId().toString().equalsIgnoreCase(build.appId()) || !"AppFleet".equals(manifest.technicalName()) || manifest.installer().type() != PackageType.INNO) throw new IllegalArgumentException("Релиз AppFleet не соответствует ожидаемому стандарту обновления");
        ReleaseAsset installer = release.assets().stream().filter(asset -> asset.name().equals(manifest.installer().assetName())).findFirst().orElseThrow();
        return Optional.of(new SelfUpdateOffer(build.version(), release, manifest, installer));
    }
    public boolean install(SelfUpdateOffer offer, CancellationToken cancellation, DownloadProgress progress) throws IOException {
        Optional<SelfUpdateMarker> previous = markerStore.read();
        if (previous.isPresent() && previous.get().targetVersion().equals(offer.manifest().version())) throw new IOException("Повторное самообновление до той же версии заблокировано после предыдущей попытки");
        Path operation = paths.temporaryRoot().resolve("self-update-" + UUID.randomUUID());
        DownloadedFile installer = downloader.download(offer.installer().downloadUri(), operation, offer.installer().name(), cancellation, progress);
        ReleaseAsset checksumAsset = offer.release().assets().stream().filter(asset -> asset.name().equals(offer.manifest().installer().sha256AssetName())).findFirst().orElseThrow(() -> new IOException("В релизе AppFleet отсутствует SHA-256"));
        DownloadedFile checksum = downloader.download(checksumAsset.downloadUri(), operation, checksumAsset.name(), cancellation, DownloadProgress.NONE);
        ChecksumVerifier verifier = new ChecksumVerifier();
        if (!verifier.matches(installer.path(), verifier.parseSha256Asset(checksum.path(), installer.path().getFileName().toString()))) throw new IOException("SHA-256 обновления AppFleet не совпал");
        if (new AuthenticodeVerifier().verify(installer.path()) == AuthenticodeStatus.INVALID) throw new IOException("Цифровая подпись обновления AppFleet недействительна");
        markerStore.write(new SelfUpdateMarker(offer.manifest().version(), previous.map(marker -> marker.attempts() + 1).orElse(1), operation.toString(), Instant.now()));
        new ProcessBuilder(installer.path().toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS").start();
        return true;
    }
    private AppFleetManifest readManifest(RepositoryId repository, GithubRelease release) {
        ReleaseAsset asset = release.assets().stream().filter(candidate -> candidate.name().equals("appfleet-manifest.json")).findFirst().orElseThrow(() -> new IllegalArgumentException("В релизе AppFleet отсутствует appfleet-manifest.json"));
        Path operation = paths.temporaryRoot().resolve("self-manifest-" + UUID.randomUUID());
        try {
            DownloadedFile file = downloader.download(asset.downloadUri(), operation, asset.name(), CancellationToken.NEVER_CANCELLED, DownloadProgress.NONE);
            return manifests.validate(Files.readAllBytes(file.path()), repository, release);
        } catch (IOException failure) { throw new IllegalArgumentException("Не удалось загрузить manifest обновления AppFleet", failure); }
        finally { deleteDirectory(operation); }
    }
    private static void deleteDirectory(Path directory) { try { if (!Files.exists(directory)) return; try (var walk = Files.walk(directory)) { walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } } catch (IOException ignored) { } }
}
