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
import ru.pashaapps.appfleet.persistence.OperationDirectory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    private final OperationCoordinator operations;

    public SelfUpdateService(BuildInfo build, AppPaths paths, ObjectMapper mapper) {
        this(build, paths, mapper, new OperationCoordinator());
    }

    public SelfUpdateService(BuildInfo build, AppPaths paths, ObjectMapper mapper, OperationCoordinator operations) {
        this.build = build;
        this.paths = paths;
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.github = new GithubApiClient(http, mapper, build.version());
        this.downloader = new GithubAssetDownloader(http, "AppFleet/" + build.version());
        this.manifests = new ManifestValidator(mapper);
        this.markerStore = new AtomicJsonStore<>(mapper, SelfUpdateMarker.class, paths.cacheDirectory().resolve("self-update.json"));
        this.operations = java.util.Objects.requireNonNull(operations, "operations");
    }

    public void recoverAfterLaunch() {
        markerStore.read().ifPresent(marker -> {
            Optional<SemVersion> running = SemVersion.tryParse(build.version());
            Optional<SemVersion> target = SemVersion.tryParse(marker.targetVersion());
            if (running.isPresent() && target.isPresent() && running.get().equals(target.get())) {
                cleanMarkedOperation(marker);
                clearMarker();
                log.info("Самообновление до {} подтверждено запуском новой версии", marker.targetVersion());
            } else {
                cleanMarkedOperation(marker);
                clearMarker();
                log.warn("Предыдущее самообновление до {} не подтвердилось. Повторная попытка доступна пользователю.", marker.targetVersion());
            }
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
        return install(offer, cancellation, progress, OperationProgress.NONE);
    }

    public boolean install(SelfUpdateOffer offer, CancellationToken cancellation, DownloadProgress progress, OperationProgress operationProgress) throws IOException {
        OperationCoordinator.Lease lease = operations.tryAcquire(OperationCoordinator.OperationKind.SELF_UPDATE, "AppFleet " + offer.manifest().version())
                .orElseThrow(() -> new IOException("Уже выполняется другая установка или самообновление AppFleet. Дождитесь её завершения."));
        OperationDirectory operation = OperationDirectory.create(paths.temporaryRoot(), "self-update-");
        try (lease) {
            operationProgress.phaseChanged(OperationPhase.DOWNLOADING);
            DownloadedFile installer = downloader.download(offer.installer().downloadUri(), operation.path(), offer.installer().name(), cancellation, progress);
            ReleaseAsset checksumAsset = offer.release().assets().stream().filter(asset -> asset.name().equals(offer.manifest().installer().sha256AssetName())).findFirst().orElseThrow(() -> new IOException("В релизе AppFleet отсутствует SHA-256"));
            DownloadedFile checksum = downloader.download(checksumAsset.downloadUri(), operation.path(), checksumAsset.name(), cancellation, DownloadProgress.NONE);
            ChecksumVerifier verifier = new ChecksumVerifier();
            cancellation.throwIfCancelled();
            operationProgress.phaseChanged(OperationPhase.VERIFYING);
            if (!verifier.matches(installer.path(), verifier.parseSha256Asset(checksum.path(), installer.path().getFileName().toString()))) throw new IOException("SHA-256 обновления AppFleet не совпал");
            if (new AuthenticodeVerifier().verify(installer.path()) == AuthenticodeStatus.INVALID) throw new IOException("Цифровая подпись обновления AppFleet недействительна");

            // This marker represents an unconfirmed external installer launch, not a retry lock.
            cancellation.throwIfCancelled();
            markerStore.write(new SelfUpdateMarker(offer.manifest().version(), 1, operation.path().toString(), Instant.now()));
            try {
                operationProgress.phaseChanged(OperationPhase.LAUNCHING_INSTALLER);
                new ProcessBuilder(commandFor(installer.path())).start();
                operation.detach();
                return true;
            } catch (IOException | RuntimeException failure) {
                clearMarker();
                throw failure;
            }
        } finally {
            try {
                operation.close();
            } catch (IOException cleanupFailure) {
                log.warn("Не удалось очистить временный каталог самообновления", cleanupFailure);
            }
            operationProgress.phaseChanged(OperationPhase.FINISHED);
        }
    }

    static List<String> commandFor(Path installer) {
        return List.of(installer.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/APPFLEETSELFUPDATE");
    }
    private AppFleetManifest readManifest(RepositoryId repository, GithubRelease release) {
        ReleaseAsset asset = release.assets().stream().filter(candidate -> candidate.name().equals("appfleet-manifest.json")).findFirst().orElseThrow(() -> new IllegalArgumentException("В релизе AppFleet отсутствует appfleet-manifest.json"));
        try (OperationDirectory operation = OperationDirectory.create(paths.temporaryRoot(), "self-manifest-")) {
            DownloadedFile file = downloader.download(asset.downloadUri(), operation.path(), asset.name(), CancellationToken.NEVER_CANCELLED, DownloadProgress.NONE);
            return manifests.validate(Files.readAllBytes(file.path()), repository, release);
        } catch (IOException failure) { throw new IllegalArgumentException("Не удалось загрузить manifest обновления AppFleet", failure); }
    }

    private void cleanMarkedOperation(SelfUpdateMarker marker) {
        try {
            boolean removed = OperationDirectory.deleteRecovered(paths.temporaryRoot(), marker.operationDirectory(), "self-update-");
            if (!removed && marker.operationDirectory() != null && !marker.operationDirectory().isBlank()) {
                log.warn("Не удалён временный каталог самообновления: marker не подтверждает владение {}", marker.operationDirectory());
            }
        } catch (IOException failure) {
            log.warn("Не удалось очистить подтверждённый временный каталог самообновления", failure);
        }
    }

    private void clearMarker() {
        try {
            markerStore.delete();
        } catch (IOException failure) {
            log.warn("Не удалось очистить marker самообновления", failure);
        }
    }
}
