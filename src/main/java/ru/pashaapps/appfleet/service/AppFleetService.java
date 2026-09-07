package ru.pashaapps.appfleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pashaapps.appfleet.domain.*;
import ru.pashaapps.appfleet.github.GithubApiClient;
import ru.pashaapps.appfleet.github.GithubApiException;
import ru.pashaapps.appfleet.github.GithubResponse;
import ru.pashaapps.appfleet.install.*;
import ru.pashaapps.appfleet.persistence.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stateful application coordinator. Network and installation work never runs on the JavaFX thread. */
public final class AppFleetService implements AutoCloseable {
    private final AppPaths paths;
    private final GithubApiClient github;
    private final GithubAssetDownloader downloader;
    private final ManifestValidator manifests;
    private final String appFleetVersion;
    private final OperationCoordinator operations;
    private final AtomicJsonStore<RepositoriesDocument> repositoriesStore;
    private final AtomicJsonStore<UserSettings> settingsStore;
    private final AtomicJsonStore<ReleaseCache> releaseCacheStore;
    private final OperationJournal journal;
    private final WindowsRegistryDetector registry;
    private final AssetSelector selector = new AssetSelector();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("appfleet-worker-", 0).factory());
    private final Map<String, ApplicationSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, ReleaseCache.Entry> releaseCache = new LinkedHashMap<>();
    private final Map<String, Instant> retryAfter = new HashMap<>();
    private PendingInstallation pendingInstallation;
    private RepositoriesDocument repositories;
    private UserSettings settings;

    public AppFleetService(AppPaths paths, ObjectMapper mapper, String version) {
        this(paths, mapper, version, new OperationCoordinator());
    }

    public AppFleetService(AppPaths paths, ObjectMapper mapper, String version, OperationCoordinator operations) {
        this(paths, mapper, version, defaultHttpClient(), operations);
    }

    private AppFleetService(AppPaths paths, ObjectMapper mapper, String version, HttpClient http, OperationCoordinator operations) {
        this(paths, mapper, version, new GithubApiClient(http, mapper, version), new GithubAssetDownloader(http, "AppFleet/" + version), operations);
    }

    AppFleetService(AppPaths paths, ObjectMapper mapper, String version, GithubApiClient github, GithubAssetDownloader downloader) {
        this(paths, mapper, version, github, downloader, new OperationCoordinator());
    }

    AppFleetService(AppPaths paths, ObjectMapper mapper, String version, GithubApiClient github, GithubAssetDownloader downloader, OperationCoordinator operations) {
        this.paths = paths;
        this.github = github;
        this.downloader = downloader;
        this.manifests = new ManifestValidator(mapper);
        this.appFleetVersion = version;
        this.operations = Objects.requireNonNull(operations, "operations");
        this.repositoriesStore = new AtomicJsonStore<>(mapper, RepositoriesDocument.class, paths.repositoriesFile());
        this.settingsStore = new AtomicJsonStore<>(mapper, UserSettings.class, paths.settingsFile());
        this.releaseCacheStore = new AtomicJsonStore<>(mapper, ReleaseCache.class, paths.cacheDirectory().resolve("release-cache.json"));
        this.journal = new OperationJournal(new AtomicJsonStore<>(mapper, OperationsDocument.class, paths.operationsFile()));
        this.registry = new WindowsRegistryDetector();
        this.repositories = repositoriesStore.read().orElseGet(RepositoriesDocument::empty);
        this.settings = settingsStore.read().orElseGet(UserSettings::defaults).normalized();
        releaseCacheStore.read().orElseGet(ReleaseCache::empty).entries().forEach(entry -> releaseCache.put(entry.repositoryKey(), entry));
    }

    private static HttpClient defaultHttpClient() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(); }

    public synchronized UserSettings settings() { return settings; }
    public synchronized void saveSettings(UserSettings candidate) throws IOException { settings = candidate.normalized(); settingsStore.write(settings); }
    public synchronized List<OperationEntry> journalEntries() { return journal.entries(); }
    public void record(String repository, String operation, String result, String message, Throwable technical) { journal.write(repository, operation, result, message, technical); }
    public synchronized List<ApplicationSnapshot> currentSnapshots() { return List.copyOf(snapshots.values()); }
    public CompletableFuture<List<ApplicationSnapshot>> checkAll() {
        return CompletableFuture.supplyAsync(() -> {
            journal.write("AppFleet", "Проверка репозиториев", "Начата", "Начата проверка " + repositories.repositories().size() + " репозиториев", null);
            List<ApplicationSnapshot> checked = new ArrayList<>();
            for (RepositoryState state : repositories.repositories()) checked.add(check(state));
            synchronized (this) {
                saveRepositories(checked.stream().map(ApplicationSnapshot::persisted).toList());
                snapshots.clear();
                checked.forEach(snapshot -> snapshots.put(snapshot.repository().normalizedKey(), snapshot));
            }
            journal.write("AppFleet", "Проверка репозиториев", "Успешно", "Проверка репозиториев завершена", null);
            return List.copyOf(checked);
        }, worker);
    }
    public CompletableFuture<ApplicationSnapshot> addRepository(String rawUrl) {
        return CompletableFuture.supplyAsync(() -> {
            RepositoryId id = RepositoryId.fromGithubUrl(rawUrl);
            synchronized (this) {
                if (repositories.repositories().stream().anyMatch(state -> (state.owner() + "/" + state.repository()).equalsIgnoreCase(id.slug()))) {
                    ApplicationSnapshot existing = snapshots.get(id.normalizedKey());
                    if (existing != null) return existing;
                    throw new IllegalArgumentException("Этот репозиторий уже добавлен: " + id.slug());
                }
            }
            GithubResponse<?> repository = github.getRepository(id, null);
            RepositoryState state = emptyState(id);
            ApplicationSnapshot checked = check(state);
            synchronized (this) {
                List<RepositoryState> updated = new ArrayList<>(repositories.repositories());
                updated.add(checked.persisted());
                saveRepositories(updated);
                snapshots.put(id.normalizedKey(), checked);
            }
            journal.write(id.slug(), "Добавление репозитория", "Успешно", "Репозиторий добавлен", null);
            return checked;
        }, worker);
    }
    public CompletableFuture<Void> removeRepository(RepositoryId id) {
        return CompletableFuture.runAsync(() -> synchronizedUpdateRepositories(repositories.repositories().stream().filter(state -> !(state.owner() + "/" + state.repository()).equalsIgnoreCase(id.slug())).toList()), worker)
                .thenRun(() -> { forgetCachedRelease(id); synchronized (this) { snapshots.remove(id.normalizedKey()); } journal.write(id.slug(), "Удаление из списка", "Успешно", "Приложение удалено только из списка AppFleet", null); });
    }
    public CompletableFuture<ApplicationSnapshot> chooseAsset(RepositoryId id, ReleaseAsset selected) {
        return CompletableFuture.supplyAsync(() -> {
            ApplicationSnapshot current = requireSnapshot(id);
            if (current.release() == null || selector.eligibleAssets(current.release()).stream().noneMatch(asset -> asset.id() == selected.id())) throw new IllegalArgumentException("Выбранный файл не является подходящим Windows x64 пакетом текущего релиза");
            RepositoryState state = withRule(current.persisted(), AssetSelectionRule.from(selected));
            synchronizedUpdateRepositories(repositories.repositories().stream().map(existing -> same(existing, id) ? state : existing).toList());
            ApplicationSnapshot refreshed = check(state);
            synchronized (this) { snapshots.put(id.normalizedKey(), refreshed); }
            journal.write(id.slug(), "Выбор файла", "Успешно", "Выбран файл " + selected.name(), null);
            return refreshed;
        }, worker);
    }
    public OperationPreview preview(RepositoryId id) { return preview(requireSnapshot(id)); }

    public InstallationPlan prepareInstallation(RepositoryId id, OperationRequest request) {
        if (!request.confirmed()) {
            throw new IllegalArgumentException("Операция не подтверждена пользователем");
        }
        ApplicationSnapshot snapshot = requireSnapshot(id);
        MinimumAppFleetVersion.requireSupported(appFleetVersion, snapshot.manifest());
        return new InstallationPlan(UUID.randomUUID(), snapshot, preview(snapshot), request, settings(), Instant.now());
    }

    private OperationPreview preview(ApplicationSnapshot snapshot) {
        if (snapshot.selectedAsset() == null || snapshot.release() == null) throw new IllegalStateException("Для приложения сначала требуется выбрать файл релиза");
        PackageType type = snapshot.manifest() == null ? snapshot.selectedAsset().packageType() : snapshot.manifest().installer().type();
        boolean checksum = snapshot.manifest() != null && snapshot.manifest().installer().sha256AssetName() != null
                || snapshot.release().assets().stream().anyMatch(asset -> asset.name().equals(snapshot.selectedAsset().name() + ".sha256"));
        return new OperationPreview(snapshot, snapshot.installedVersion(), snapshot.release().tagName(), snapshot.selectedAsset().name(), snapshot.selectedAsset().size(), snapshot.release().body(), snapshot.release().htmlUrl().toString(), checksum, snapshot.manifest() == null && type == PackageType.EXE, type);
    }
    public CompletableFuture<OperationResult> installOrUpdate(RepositoryId id, OperationRequest request, CancellationToken cancellation, DownloadProgress progress) {
        if (!request.confirmed()) {
            ApplicationSnapshot snapshot = requireSnapshot(id);
            return CompletableFuture.completedFuture(OperationResult.cancelled("Операция отменена", snapshot));
        }
        return installOrUpdate(prepareInstallation(id, request), cancellation, progress);
    }

    public CompletableFuture<OperationResult> installOrUpdate(InstallationPlan plan, CancellationToken cancellation, DownloadProgress progress) {
        return installOrUpdate(plan, cancellation, progress, OperationProgress.NONE);
    }

    public CompletableFuture<OperationResult> installOrUpdate(InstallationPlan plan, CancellationToken cancellation, DownloadProgress progress, OperationProgress operationProgress) {
        OperationCoordinator.Lease lease = operations.tryAcquire(OperationCoordinator.OperationKind.APPLICATION_INSTALL, plan.snapshot().repository().slug())
                .orElse(null);
        if (lease == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Уже выполняется другая установка или самообновление AppFleet. Дождитесь её завершения."));
        }
        return CompletableFuture.supplyAsync(() -> beginInstall(plan, cancellation, progress, operationProgress, lease), worker);
    }

    public CompletableFuture<OperationResult> continueAfterForceClose(ForceCloseContinuation continuation, boolean accepted, OperationProgress operationProgress) {
        PendingInstallation pending = takePendingInstallation(continuation);
        if (pending == null) return CompletableFuture.failedFuture(new IllegalStateException("Подтверждение закрытия уже обработано или устарело."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!accepted) {
                    journal.write(pending.plan().snapshot().repository().slug(), "Закрытие приложения", "Отменено", "Пользователь не разрешил принудительное завершение", null);
                    return OperationResult.cancelled("Операция отменена пользователем", pending.plan().snapshot());
                }
                List<RunningApplication> closed = forceCloseExactProcesses(pending.plan().snapshot(), pending.forceCloseProcesses());
                List<RunningApplication> restarted = new ArrayList<>(pending.gracefullyClosed());
                restarted.addAll(closed);
                return launchVerifiedInstaller(pending.plan(), pending.installer(), pending.signature(), pending.operationDirectory(), restarted, operationProgress);
            } catch (Exception failure) {
                journal.write(pending.plan().snapshot().repository().slug(), "Установка", "Ошибка", "Установка не выполнена: " + failure.getMessage(), failure);
                return new OperationResult(false, false, "Установка не выполнена: " + failure.getMessage(), pending.plan().snapshot());
            } finally {
                operationProgress.phaseChanged(OperationPhase.FINISHED);
                pending.lease().close();
            }
        }, worker);
    }

    private ApplicationSnapshot check(RepositoryState original) {
        RepositoryId id = new RepositoryId(original.owner(), original.repository());
        ApplicationSnapshot throttled = cachedSnapshotDuringCooldown(id, original);
        if (throttled != null) return throttled;
        try {
            journal.write(id.slug(), "Проверка репозитория", "Начата", "Запрашивается последний stable-релиз", null);
            boolean canReuseRetainedSnapshot = snapshots.containsKey(id.normalizedKey()) && original.releaseEtag() != null;
            GithubResponse<List<GithubRelease>> response = github.listReleases(id, canReuseRetainedSnapshot ? original.releaseEtag() : null);
            if (response.notModified()) {
                ApplicationSnapshot retained = snapshots.get(id.normalizedKey());
                if (retained != null) return retained;
                ApplicationSnapshot cached = cachedSnapshot(id, original, "GitHub подтвердил, что данные не изменились; показаны сохранённые сведения");
                if (cached != null) return cached;
                return new ApplicationSnapshot(id, original, AppStatus.CHECK_ERROR, id.repository(), original.installedVersion(), null, null, List.of(), null, "Данные релиза не сохранены, нужна повторная проверка");
            }
            Optional<GithubRelease> stable = response.body().stream().filter(GithubRelease::isStable).max(Comparator.comparing(GithubRelease::publishedAt));
            RepositoryState state = withCheck(original, response.etag(), stable.isPresent() ? "Успешно" : "Релизы не найдены");
            clearRetryAfter(id);
            if (stable.isEmpty()) {
                forgetCachedRelease(id);
                return snapshot(id, state, AppStatus.NO_RELEASES, null, null, List.of(), null, "Stable-релизы отсутствуют");
            }
            GithubRelease release = stable.get();
            ManifestResult manifest = readManifestIfPresent(id, release);
            AssetSelection selection = manifest.manifest == null ? selector.select(release, toRule(state)) : manifest.selection;
            ReleaseAsset asset = selection instanceof AssetSelection.Selected selected ? selected.asset() : null;
            List<ReleaseAsset> candidates = selection instanceof AssetSelection.NeedsChoice choice ? choice.candidates() : List.of();
            AppStatus status = statusFor(state, release, asset, candidates, manifest.manifest);
            String message = manifest.failure == null ? messageFor(selection, status) : "Манифест релиза не принят: " + manifest.failure + ". Выполнен анализ файлов.";
            ApplicationSnapshot checked = snapshot(id, state, status, release, asset, candidates, manifest.manifest, message);
            rememberRelease(id, release, manifest.manifest);
            journal.write(id.slug(), "Проверка репозитория", "Успешно", message, null);
            return checked;
        } catch (Exception failure) {
            if (failure instanceof GithubApiException githubFailure) {
                githubFailure.retryAt().filter(time -> time.isAfter(Instant.now())).ifPresent(time -> rememberRetryAfter(id, time));
                ApplicationSnapshot cached = cachedSnapshot(id, original, "Не удалось обновить сведения GitHub: " + failure.getMessage() + ". Показаны последние подтверждённые данные.");
                if (cached != null) {
                    journal.write(id.slug(), "Проверка репозитория", "Предупреждение", cached.message(), failure);
                    return cached;
                }
            }
            RepositoryState state = withCheck(original, original.releaseEtag(), "Ошибка: " + failure.getMessage());
            journal.write(id.slug(), "Проверка репозитория", "Ошибка", "Не удалось проверить репозиторий", failure);
            return snapshot(id, state, AppStatus.CHECK_ERROR, null, null, List.of(), null, failure.getMessage());
        }
    }
    private ManifestResult readManifestIfPresent(RepositoryId id, GithubRelease release) {
        Optional<ReleaseAsset> asset = release.assets().stream().filter(candidate -> candidate.name().equals("appfleet-manifest.json")).findFirst();
        if (asset.isEmpty()) return new ManifestResult(null, null, null);
        Path operation = paths.temporaryRoot().resolve("manifest-" + UUID.randomUUID());
        try {
            DownloadedFile downloaded = downloader.download(asset.get().downloadUri(), operation, "appfleet-manifest.json", CancellationToken.NEVER_CANCELLED, DownloadProgress.NONE);
            AppFleetManifest manifest = manifests.validate(Files.readAllBytes(downloaded.path()), id, release);
            ReleaseAsset selected = release.assets().stream().filter(candidate -> candidate.name().equals(manifest.installer().assetName())).findFirst().orElseThrow();
            return new ManifestResult(manifest, new AssetSelection.Selected(selected, true), null);
        } catch (Exception invalid) { return new ManifestResult(null, null, invalid.getMessage());
        } finally { deleteOperationDirectory(operation); }
    }
    private OperationResult beginInstall(InstallationPlan plan, CancellationToken cancellation, DownloadProgress progress, OperationProgress operationProgress, OperationCoordinator.Lease lease) {
        ApplicationSnapshot snapshot = plan.snapshot();
        OperationPreview preview = plan.preview();
        RepositoryId id = snapshot.repository();
        Path operation = paths.temporaryRoot().resolve("operation-" + UUID.randomUUID());
        boolean waitingForForceConsent = false;
        try {
            operationProgress.phaseChanged(OperationPhase.PREPARING);
            cancellation.throwIfCancelled();
            operationProgress.phaseChanged(OperationPhase.DOWNLOADING);
            journal.write(id.slug(), "Скачивание", "Начата", "Скачивается " + preview.assetName(), null);
            DownloadedFile installer = downloader.download(snapshot.selectedAsset().downloadUri(), operation, snapshot.selectedAsset().name(), cancellation, progress);
            cancellation.throwIfCancelled();
            operationProgress.phaseChanged(OperationPhase.VERIFYING);
            AuthenticodeStatus signature = verifyDownloadedFile(snapshot, installer, operation, cancellation);
            cancellation.throwIfCancelled();
            ProcessClosePreparation closePreparation = closeRunningProcesses(snapshot, plan.request());
            if (!closePreparation.forceCloseProcesses().isEmpty() && !plan.request().forceCloseIfNeeded()) {
                ForceCloseContinuation continuation = new ForceCloseContinuation(UUID.randomUUID(), snapshot.displayName(), closePreparation.forceCloseProcesses());
                putPendingInstallation(new PendingInstallation(continuation, plan, installer, signature, operation, closePreparation.gracefullyClosed(), closePreparation.forceCloseProcesses(), lease));
                waitingForForceConsent = true;
                return OperationResult.forceCloseConfirmationRequired(snapshot, continuation);
            }
            List<RunningApplication> closed = new ArrayList<>(closePreparation.gracefullyClosed());
            if (!closePreparation.forceCloseProcesses().isEmpty()) closed.addAll(forceCloseExactProcesses(snapshot, closePreparation.forceCloseProcesses()));
            return launchVerifiedInstaller(plan, installer, signature, operation, closed, operationProgress);
        } catch (OperationCancelledException cancelled) {
            journal.write(id.slug(), "Установка", "Отменена", "Операция отменена пользователем", null);
            return OperationResult.cancelled("Операция отменена", snapshot);
        } catch (Exception failure) {
            journal.write(id.slug(), "Установка", "Ошибка", "Установка не выполнена: " + failure.getMessage(), failure);
            return new OperationResult(false, false, "Установка не выполнена: " + failure.getMessage(), snapshot);
        } finally {
            operationProgress.phaseChanged(OperationPhase.FINISHED);
            if (!waitingForForceConsent) lease.close();
        }
    }

    private OperationResult launchVerifiedInstaller(InstallationPlan plan, DownloadedFile installer, AuthenticodeStatus signature, Path operation,
                                                     List<RunningApplication> previouslyRunning, OperationProgress operationProgress) throws IOException {
        ApplicationSnapshot snapshot = plan.snapshot();
        OperationPreview preview = plan.preview();
        RepositoryId id = snapshot.repository();
        boolean firstInstallation = snapshot.status() == AppStatus.NOT_INSTALLED;
        boolean desktopShortcutRequested = firstInstallation && plan.settings().createDesktopShortcutForNewApplications();
        String desktopShortcutTask = desktopShortcutTask(snapshot, preview.packageType());
        operationProgress.phaseChanged(OperationPhase.LAUNCHING_INSTALLER);
        journal.write(id.slug(), "Установка", "Начата", "Запускается " + preview.assetName(), null);
        InstallerExit exit;
        Optional<InstalledApplication> detectedInstallation = Optional.empty();
        if (preview.packageType() == PackageType.ZIP) {
            new ManagedZipInstaller(paths.programDirectory().getParent().getParent().resolve("AppFleetManaged")).install(installer.path(), id, CancellationToken.NEVER_CANCELLED);
            exit = InstallerExit.forGeneric(0);
        } else {
            List<String> baseArguments = snapshot.manifest() == null ? List.of() : snapshot.manifest().installer().silentArgs();
            List<String> arguments = withDesktopShortcutTask(baseArguments, firstInstallation, desktopShortcutRequested, desktopShortcutTask);
            journal.write(id.slug(), "Запуск установщика", "Начата", "Запускается " + preview.assetName() + (arguments.isEmpty() ? "" : " с параметрами " + String.join(" ", arguments)), null);
            operationProgress.phaseChanged(OperationPhase.WAITING_FOR_INSTALLER);
            exit = new ExternalInstallerRunner().run(installer.path(), preview.packageType(), arguments);
            journal.write(id.slug(), "Запуск установщика", "Завершён", "Установщик завершил основной процесс с кодом " + exit.code(), null);
            if (!exit.successful()) throw new IOException(exit.message());
            if (snapshot.manifest() != null && snapshot.manifest().detection() != null) {
                detectedInstallation = Optional.of(new StandardInstallationAwaiter(registry).await(snapshot.manifest().appId(), snapshot.release().tagName(), Duration.ofSeconds(60)));
            }
        }
        restartPreviouslyRunning(snapshot, previouslyRunning, detectedInstallation, plan.settings());
        RepositoryState installed = withInstalled(snapshot.persisted(), snapshot, preview.packageType(), detectedInstallation);
        synchronizedUpdateRepositories(repositories.repositories().stream().map(existing -> same(existing, id) ? installed : existing).toList());
        ApplicationSnapshot updated = check(installed);
        synchronized (this) { snapshots.put(id.normalizedKey(), updated); }
        if (plan.settings().deleteInstallerAfterSuccess()) deleteOperationDirectory(operation);
        String resultMessage = exit.message();
        if (signature == AuthenticodeStatus.NOT_SIGNED) {
            String warning = "Установщик не имеет цифровой подписи. AppFleet проверил SHA-256 и продолжил установку.";
            journal.write(id.slug(), "Проверка файла", "Предупреждение", warning, null);
            resultMessage += "\n\nПредупреждение: " + warning;
        }
        if (desktopShortcutRequested && desktopShortcutTask == null) {
            String warning = "Ярлык на рабочем столе не создан: manifest приложения не объявляет поддерживаемую Inno Setup task.";
            journal.write(id.slug(), "Ярлык на рабочем столе", "Предупреждение", warning, null);
            resultMessage += "\n\nПредупреждение: " + warning;
        }
        journal.write(id.slug(), "Установка", "Успешно", resultMessage, null);
        return new OperationResult(true, exit.restartRequired(), resultMessage, updated);
    }

    static List<String> withDesktopShortcutTask(List<String> baseArguments, boolean firstInstallation, boolean requested, String taskName) {
        if (!firstInstallation || !requested || taskName == null || taskName.isBlank()) return List.copyOf(baseArguments);
        List<String> arguments = new ArrayList<>(baseArguments);
        arguments.add("/TASKS=" + taskName);
        return List.copyOf(arguments);
    }

    private static String desktopShortcutTask(ApplicationSnapshot snapshot, PackageType packageType) {
        if (packageType != PackageType.INNO || snapshot.manifest() == null) return null;
        return snapshot.manifest().installer().desktopShortcutTask();
    }
    private AuthenticodeStatus verifyDownloadedFile(ApplicationSnapshot snapshot, DownloadedFile downloaded, Path operation, CancellationToken cancellation) throws IOException {
        ChecksumVerifier checksums = new ChecksumVerifier();
        String checksumName = snapshot.manifest() == null ? downloaded.path().getFileName() + ".sha256" : snapshot.manifest().installer().sha256AssetName();
        Optional<ReleaseAsset> checksumAsset = checksumName == null ? Optional.empty() : snapshot.release().assets().stream().filter(asset -> asset.name().equals(checksumName)).findFirst();
        if (snapshot.manifest() != null && checksumAsset.isEmpty()) throw new IOException("Для стандартного приложения отсутствует обязательный SHA-256");
        if (checksumAsset.isPresent()) {
            DownloadedFile checksum = downloader.download(checksumAsset.get().downloadUri(), operation, checksumAsset.get().name(), cancellation, DownloadProgress.NONE);
            String expected = checksums.parseSha256Asset(checksum.path(), downloaded.path().getFileName().toString());
            if (!checksums.matches(downloaded.path(), expected)) throw new IOException("SHA-256 скачанного файла не совпал");
            journal.write(snapshot.repository().slug(), "Проверка файла", "Успешно", "SHA-256 совпал", null);
        }
        if (snapshot.selectedAsset().packageType() == PackageType.EXE) {
            AuthenticodeStatus signature = new AuthenticodeVerifier().verify(downloaded.path());
            if (signature == AuthenticodeStatus.INVALID) throw new IOException("Цифровая подпись установщика недействительна");
            journal.write(snapshot.repository().slug(), "Проверка файла", "Успешно", "Authenticode: " + signature, null);
            return signature;
        }
        return AuthenticodeStatus.UNAVAILABLE;
    }
    private ProcessClosePreparation closeRunningProcesses(ApplicationSnapshot snapshot, OperationRequest request) throws IOException {
        Path executable = verifiedInstalledExecutable(snapshot).orElse(null);
        if (executable == null) return ProcessClosePreparation.empty();
        Set<String> names = snapshot.manifest() != null ? new LinkedHashSet<>(snapshot.manifest().processNames()) : snapshot.persisted().processNames();
        ProcessManager processes = new ProcessManager();
        List<RunningApplication> running = processes.findByVerifiedExecutable(executable, names);
        if (running.isEmpty()) return ProcessClosePreparation.empty();
        if (!request.closeRunningApplications()) throw new IOException("Приложение запущено; выберите «Закрыть автоматически» или отмените операцию");
        List<RunningApplication> gracefullyClosed = new ArrayList<>();
        List<RunningApplication> forceClose = new ArrayList<>();
        for (RunningApplication process : running) {
            switch (processes.requestGracefulClose(process, Duration.ofSeconds(10))) {
                case CLOSED -> {
                    gracefullyClosed.add(process);
                    journal.write(snapshot.repository().slug(), "Закрытие приложения", "Успешно", "Приложение штатно завершило процесс " + process.pid(), null);
                }
                case NOT_RUNNING -> { /* The process ended on its own; it does not need restarting. */ }
                case NO_TOP_LEVEL_WINDOW, STILL_RUNNING -> forceClose.add(process);
                case IDENTITY_CHANGED -> throw new IOException("Процесс приложения изменился до закрытия; AppFleet не будет завершать другой процесс");
            }
        }
        return new ProcessClosePreparation(gracefullyClosed, forceClose);
    }

    private List<RunningApplication> forceCloseExactProcesses(ApplicationSnapshot snapshot, List<RunningApplication> selected) throws IOException {
        ProcessManager processes = new ProcessManager();
        List<RunningApplication> closed = new ArrayList<>();
        for (RunningApplication process : selected) {
            switch (processes.forceCloseAfterExplicitConsent(process, Duration.ofSeconds(5))) {
                case CLOSED -> {
                    closed.add(process);
                    journal.write(snapshot.repository().slug(), "Закрытие приложения", "Успешно", "Принудительно завершён ранее подтверждённый процесс " + process.pid(), null);
                }
                case NOT_RUNNING -> { /* It ended while the user considered the dialog. */ }
                case IDENTITY_CHANGED -> throw new IOException("Процесс приложения изменился после подтверждения; AppFleet не будет завершать другой процесс");
                case STILL_RUNNING -> throw new IOException("Не удалось принудительно завершить ранее подтверждённый процесс " + process.pid());
            }
        }
        return closed;
    }

    private Optional<Path> verifiedInstalledExecutable(ApplicationSnapshot snapshot) {
        String saved = snapshot.persisted().executable();
        if (saved == null || saved.isBlank()) return Optional.empty();
        try {
            Path executable = Path.of(saved).toAbsolutePath().normalize();
            return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
        } catch (RuntimeException malformedPath) {
            return Optional.empty();
        }
    }

    private void restartPreviouslyRunning(ApplicationSnapshot snapshot, List<RunningApplication> previouslyRunning,
                                          Optional<InstalledApplication> detectedInstallation, UserSettings operationSettings) {
        if (!operationSettings.restartPreviouslyRunningApp() || previouslyRunning.isEmpty()) return;
        if (detectedInstallation.isEmpty() || !Files.isRegularFile(detectedInstallation.get().executable())) {
            journal.write(snapshot.repository().slug(), "Перезапуск приложения", "Предупреждение", "Перезапуск пропущен: новый главный EXE не подтверждён реестром", null);
            return;
        }
        Path executable = detectedInstallation.get().executable();
        try {
            new ProcessBuilder(executable.toString()).start();
            journal.write(snapshot.repository().slug(), "Перезапуск приложения", "Успешно", "Повторно запущен подтверждённый EXE " + executable, null);
        } catch (IOException failure) {
            journal.write(snapshot.repository().slug(), "Перезапуск приложения", "Ошибка", "Не удалось повторно запустить подтверждённый EXE", failure);
        }
    }
    private AppStatus statusFor(RepositoryState state, GithubRelease release, ReleaseAsset asset, List<ReleaseAsset> candidates, AppFleetManifest manifest) {
        if (asset == null) return candidates.isEmpty() ? AppStatus.USER_ACTION_REQUIRED : AppStatus.ASSET_SELECTION_REQUIRED;
        String installed = installedVersion(state, manifest);
        if (installed == null || installed.isBlank()) return AppStatus.NOT_INSTALLED;
        Optional<SemVersion> knownInstalled = SemVersion.tryParse(installed);
        Optional<SemVersion> target = SemVersion.tryParse(release.tagName());
        if (knownInstalled.isPresent() && target.isPresent()) return target.get().compareTo(knownInstalled.get()) > 0 ? AppStatus.UPDATE_AVAILABLE : AppStatus.UP_TO_DATE;
        return installed.equals(release.tagName()) ? AppStatus.UP_TO_DATE : AppStatus.VERSION_UNKNOWN;
    }
    private String installedVersion(RepositoryState state, AppFleetManifest manifest) {
        if (manifest != null) try { return registry.findStandardApplication(manifest.appId()).map(InstalledApplication::version).orElse(state.installedVersion()); } catch (IOException ignored) { return state.installedVersion(); }
        return state.installedVersion();
    }
    private RepositoryState withDetectedStandard(RepositoryState state, AppFleetManifest manifest) {
        if (manifest == null) return state;
        try {
            return registry.findStandardApplication(manifest.appId()).map(application -> mergeDetectedStandard(state, application)).orElse(state);
        } catch (IOException ignored) {
            return state;
        }
    }
    static RepositoryState mergeDetectedStandard(RepositoryState state, InstalledApplication application) {
        Set<String> processNames = state.processNames().isEmpty() && application.processName() != null && !application.processName().isBlank()
                ? Set.of(application.processName()) : state.processNames();
        return new RepositoryState(state.schemaVersion(), state.owner(), state.repository(), state.canonicalUrl(), state.selectedPackageType(), state.selectedArchitecture(), state.selectionTokens(),
                application.version(), state.installedAssetId(), state.installedPackageType(), application.installLocation().toString(), application.executable().toString(), processNames,
                state.releaseEtag(), state.lastCheckedAt(), state.lastCheckResult());
    }

    private ApplicationSnapshot cachedSnapshotDuringCooldown(RepositoryId id, RepositoryState state) {
        Instant until;
        synchronized (this) { until = retryAfter.get(id.normalizedKey()); }
        if (until == null || !until.isAfter(Instant.now())) return null;
        String message = "GitHub временно ограничил запросы до " + until + ". Показаны последние подтверждённые данные.";
        ApplicationSnapshot cached = cachedSnapshot(id, state, message);
        if (cached != null) {
            journal.write(id.slug(), "Проверка репозитория", "Предупреждение", message, null);
            return cached;
        }
        return null;
    }

    private ApplicationSnapshot cachedSnapshot(RepositoryId id, RepositoryState original, String message) {
        ReleaseCache.Entry entry;
        synchronized (this) { entry = releaseCache.get(id.normalizedKey()); }
        if (entry == null || !entry.release().isStable()) return null;
        AppFleetManifest manifest = entry.manifest();
        AssetSelection selection;
        if (manifest == null) {
            selection = selector.select(entry.release(), toRule(original));
        } else {
            ReleaseAsset selected = entry.release().assets().stream().filter(asset -> asset.name().equals(manifest.installer().assetName())).findFirst().orElse(null);
            selection = selected == null ? new AssetSelection.None("Сохранённый manifest ссылается на отсутствующий файл") : new AssetSelection.Selected(selected, true);
        }
        ReleaseAsset asset = selection instanceof AssetSelection.Selected selected ? selected.asset() : null;
        List<ReleaseAsset> candidates = selection instanceof AssetSelection.NeedsChoice choice ? choice.candidates() : List.of();
        RepositoryState state = withCheck(original, original.releaseEtag(), "Предупреждение: " + message);
        return snapshot(id, state, statusFor(state, entry.release(), asset, candidates, manifest), entry.release(), asset, candidates, manifest, message);
    }

    private void rememberRelease(RepositoryId id, GithubRelease release, AppFleetManifest manifest) {
        synchronized (this) {
            releaseCache.put(id.normalizedKey(), new ReleaseCache.Entry(id.normalizedKey(), release, manifest, Instant.now()));
            saveReleaseCache();
        }
    }

    private void forgetCachedRelease(RepositoryId id) {
        synchronized (this) {
            releaseCache.remove(id.normalizedKey());
            retryAfter.remove(id.normalizedKey());
            saveReleaseCache();
        }
    }

    private void rememberRetryAfter(RepositoryId id, Instant until) {
        synchronized (this) { retryAfter.put(id.normalizedKey(), until); }
    }

    private void clearRetryAfter(RepositoryId id) {
        synchronized (this) { retryAfter.remove(id.normalizedKey()); }
    }

    private ApplicationSnapshot snapshot(RepositoryId id, RepositoryState state, AppStatus status, GithubRelease release, ReleaseAsset asset, List<ReleaseAsset> candidates, AppFleetManifest manifest, String message) {
        RepositoryState discovered = withDetectedStandard(state, manifest);
        return new ApplicationSnapshot(id, discovered, status, manifest == null ? id.repository() : manifest.name(), installedVersion(discovered, manifest), release, asset, candidates, manifest, message);
    }
    private static String messageFor(AssetSelection selection, AppStatus status) { return switch (selection) { case AssetSelection.Selected selected -> selected.fromManifest() ? "Файл выбран по проверенному appfleet-manifest.json" : "Файл выбран автоматически; проверьте его перед установкой"; case AssetSelection.NeedsChoice ignored -> "Найдено несколько равнозначных файлов релиза"; case AssetSelection.None none -> none.reason(); }; }
    private synchronized ApplicationSnapshot requireSnapshot(RepositoryId id) { ApplicationSnapshot snapshot = snapshots.get(id.normalizedKey()); if (snapshot == null) throw new IllegalStateException("Сначала выполните проверку репозитория"); return snapshot; }
    private static RepositoryState emptyState(RepositoryId id) { return new RepositoryState(1, id.owner(), id.repository(), id.canonicalUrl(), null, null, Set.of(), null, null, null, null, null, Set.of(), null, null, null); }
    private static boolean same(RepositoryState state, RepositoryId id) { return (state.owner() + "/" + state.repository()).equalsIgnoreCase(id.slug()); }
    private static AssetSelectionRule toRule(RepositoryState state) { if (state.selectedPackageType() == null || state.selectedArchitecture() == null) return null; try { return new AssetSelectionRule(PackageType.valueOf(state.selectedPackageType()), Architecture.valueOf(state.selectedArchitecture()), state.selectionTokens()); } catch (IllegalArgumentException invalid) { return null; } }
    private static RepositoryState withRule(RepositoryState state, AssetSelectionRule rule) { return new RepositoryState(state.schemaVersion(), state.owner(), state.repository(), state.canonicalUrl(), rule.packageType().name(), rule.architecture().name(), rule.requiredTokens(), state.installedVersion(), state.installedAssetId(), state.installedPackageType(), state.installLocation(), state.executable(), state.processNames(), null, state.lastCheckedAt(), state.lastCheckResult()); }
    private static RepositoryState withCheck(RepositoryState state, String etag, String result) { return new RepositoryState(state.schemaVersion(), state.owner(), state.repository(), state.canonicalUrl(), state.selectedPackageType(), state.selectedArchitecture(), state.selectionTokens(), state.installedVersion(), state.installedAssetId(), state.installedPackageType(), state.installLocation(), state.executable(), state.processNames(), etag, Instant.now(), result); }
    private static RepositoryState withInstalled(RepositoryState state, ApplicationSnapshot snapshot, PackageType type, Optional<InstalledApplication> detected) {
        Set<String> names = snapshot.manifest() == null ? state.processNames() : Set.copyOf(snapshot.manifest().processNames());
        String version = detected.map(InstalledApplication::version).orElse(snapshot.release().tagName());
        String location = detected.map(application -> application.installLocation().toString()).orElse(state.installLocation());
        String executable = detected.map(application -> application.executable().toString()).orElse(state.executable());
        return new RepositoryState(state.schemaVersion(), state.owner(), state.repository(), state.canonicalUrl(), state.selectedPackageType(), state.selectedArchitecture(), state.selectionTokens(), version, snapshot.selectedAsset().id(), type.name(), location, executable, names, null, Instant.now(), "Установлено");
    }
    private synchronized void synchronizedUpdateRepositories(List<RepositoryState> updated) { saveRepositories(updated); }
    private void saveRepositories(List<RepositoryState> updated) { try { repositories = new RepositoriesDocument(1, updated); repositoriesStore.write(repositories); } catch (IOException failure) { throw new IllegalStateException("Не удалось сохранить список репозиториев", failure); } }
    private void saveReleaseCache() {
        try { releaseCacheStore.write(new ReleaseCache(1, List.copyOf(releaseCache.values()))); }
        catch (IOException failure) { journal.write("AppFleet", "Кэш релизов", "Предупреждение", "Не удалось сохранить кэш релизов", failure); }
    }
    private synchronized void putPendingInstallation(PendingInstallation pending) {
        if (pendingInstallation != null) throw new IllegalStateException("Уже ожидается подтверждение принудительного завершения");
        pendingInstallation = pending;
    }
    private synchronized PendingInstallation takePendingInstallation(ForceCloseContinuation continuation) {
        if (continuation == null || pendingInstallation == null || !pendingInstallation.continuation().id().equals(continuation.id())) return null;
        PendingInstallation pending = pendingInstallation;
        pendingInstallation = null;
        return pending;
    }
    private static void deleteOperationDirectory(Path operation) { try { if (!Files.exists(operation)) return; try (var walk = Files.walk(operation)) { walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } } catch (IOException ignored) { } }
    @Override public void close() {
        PendingInstallation pending;
        synchronized (this) {
            pending = pendingInstallation;
            pendingInstallation = null;
        }
        if (pending != null) pending.lease().close();
        worker.shutdownNow();
    }
    private record ManifestResult(AppFleetManifest manifest, AssetSelection selection, String failure) { }
    private record ProcessClosePreparation(List<RunningApplication> gracefullyClosed, List<RunningApplication> forceCloseProcesses) {
        private ProcessClosePreparation { gracefullyClosed = List.copyOf(gracefullyClosed); forceCloseProcesses = List.copyOf(forceCloseProcesses); }
        private static ProcessClosePreparation empty() { return new ProcessClosePreparation(List.of(), List.of()); }
    }
    private record PendingInstallation(ForceCloseContinuation continuation, InstallationPlan plan, DownloadedFile installer,
                                       AuthenticodeStatus signature, Path operationDirectory, List<RunningApplication> gracefullyClosed,
                                       List<RunningApplication> forceCloseProcesses, OperationCoordinator.Lease lease) { }
}
