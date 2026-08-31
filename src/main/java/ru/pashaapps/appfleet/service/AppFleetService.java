package ru.pashaapps.appfleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pashaapps.appfleet.domain.*;
import ru.pashaapps.appfleet.github.GithubApiClient;
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
    private final AtomicJsonStore<RepositoriesDocument> repositoriesStore;
    private final AtomicJsonStore<UserSettings> settingsStore;
    private final OperationJournal journal;
    private final WindowsRegistryDetector registry;
    private final AssetSelector selector = new AssetSelector();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("appfleet-worker-", 0).factory());
    private final Map<String, ApplicationSnapshot> snapshots = new LinkedHashMap<>();
    private RepositoriesDocument repositories;
    private UserSettings settings;

    public AppFleetService(AppPaths paths, ObjectMapper mapper, String version) {
        this.paths = paths;
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.github = new GithubApiClient(http, mapper, version);
        this.downloader = new GithubAssetDownloader(http, "AppFleet/" + version);
        this.manifests = new ManifestValidator(mapper);
        this.repositoriesStore = new AtomicJsonStore<>(mapper, RepositoriesDocument.class, paths.repositoriesFile());
        this.settingsStore = new AtomicJsonStore<>(mapper, UserSettings.class, paths.settingsFile());
        this.journal = new OperationJournal(new AtomicJsonStore<>(mapper, OperationsDocument.class, paths.operationsFile()));
        this.registry = new WindowsRegistryDetector();
        this.repositories = repositoriesStore.read().orElseGet(RepositoriesDocument::empty);
        this.settings = settingsStore.read().orElseGet(UserSettings::defaults).normalized();
    }

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
                .thenRun(() -> { synchronized (this) { snapshots.remove(id.normalizedKey()); } journal.write(id.slug(), "Удаление из списка", "Успешно", "Приложение удалено только из списка AppFleet", null); });
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
    public OperationPreview preview(RepositoryId id) {
        ApplicationSnapshot snapshot = requireSnapshot(id);
        if (snapshot.selectedAsset() == null || snapshot.release() == null) throw new IllegalStateException("Для приложения сначала требуется выбрать файл релиза");
        PackageType type = snapshot.manifest() == null ? snapshot.selectedAsset().packageType() : snapshot.manifest().installer().type();
        boolean checksum = snapshot.manifest() != null && snapshot.manifest().installer().sha256AssetName() != null
                || snapshot.release().assets().stream().anyMatch(asset -> asset.name().equals(snapshot.selectedAsset().name() + ".sha256"));
        return new OperationPreview(snapshot, snapshot.installedVersion(), snapshot.release().tagName(), snapshot.selectedAsset().name(), snapshot.selectedAsset().size(), snapshot.release().body(), snapshot.release().htmlUrl().toString(), checksum, snapshot.manifest() == null && type == PackageType.EXE, type);
    }
    public CompletableFuture<OperationResult> installOrUpdate(RepositoryId id, OperationRequest request, CancellationToken cancellation, DownloadProgress progress) {
        return CompletableFuture.supplyAsync(() -> performInstall(id, request, cancellation, progress), worker);
    }

    private ApplicationSnapshot check(RepositoryState original) {
        RepositoryId id = new RepositoryId(original.owner(), original.repository());
        try {
            journal.write(id.slug(), "Проверка репозитория", "Начата", "Запрашивается последний stable-релиз", null);
            boolean canReuseRetainedSnapshot = snapshots.containsKey(id.normalizedKey()) && original.releaseEtag() != null;
            GithubResponse<List<GithubRelease>> response = github.listReleases(id, canReuseRetainedSnapshot ? original.releaseEtag() : null);
            if (response.notModified()) {
                ApplicationSnapshot retained = snapshots.get(id.normalizedKey());
                if (retained != null) return retained;
                return new ApplicationSnapshot(id, original, AppStatus.CHECK_ERROR, id.repository(), original.installedVersion(), null, null, List.of(), null, "Данные релиза не сохранены, нужна повторная проверка");
            }
            Optional<GithubRelease> stable = response.body().stream().filter(GithubRelease::isStable).max(Comparator.comparing(GithubRelease::publishedAt));
            RepositoryState state = withCheck(original, response.etag(), stable.isPresent() ? "Успешно" : "Релизы не найдены");
            if (stable.isEmpty()) return snapshot(id, state, AppStatus.NO_RELEASES, null, null, List.of(), null, "Stable-релизы отсутствуют");
            GithubRelease release = stable.get();
            ManifestResult manifest = readManifestIfPresent(id, release);
            AssetSelection selection = manifest.manifest == null ? selector.select(release, toRule(state)) : manifest.selection;
            ReleaseAsset asset = selection instanceof AssetSelection.Selected selected ? selected.asset() : null;
            List<ReleaseAsset> candidates = selection instanceof AssetSelection.NeedsChoice choice ? choice.candidates() : List.of();
            AppStatus status = statusFor(state, release, asset, candidates, manifest.manifest);
            String message = manifest.failure == null ? messageFor(selection, status) : "Манифест релиза не принят: " + manifest.failure + ". Выполнен анализ файлов.";
            ApplicationSnapshot checked = snapshot(id, state, status, release, asset, candidates, manifest.manifest, message);
            journal.write(id.slug(), "Проверка репозитория", "Успешно", message, null);
            return checked;
        } catch (Exception failure) {
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
    private OperationResult performInstall(RepositoryId id, OperationRequest request, CancellationToken cancellation, DownloadProgress progress) {
        if (!request.confirmed()) { journal.write(id.slug(), "Установка", "Отменена", "Пользователь не подтвердил операцию", null); return new OperationResult(false, false, "Операция отменена", requireSnapshot(id)); }
        ApplicationSnapshot snapshot = requireSnapshot(id);
        OperationPreview preview = preview(id);
        Path operation = paths.temporaryRoot().resolve("operation-" + UUID.randomUUID());
        try {
            List<RunningApplication> previouslyRunning = handleRunningProcesses(snapshot, request);
            journal.write(id.slug(), "Скачивание", "Начата", "Скачивается " + preview.assetName(), null);
            DownloadedFile installer = downloader.download(snapshot.selectedAsset().downloadUri(), operation, snapshot.selectedAsset().name(), cancellation, progress);
            cancellation.throwIfCancelled();
            AuthenticodeStatus signature = verifyDownloadedFile(snapshot, installer, operation, cancellation);
            journal.write(id.slug(), "Установка", "Начата", "Запускается " + preview.assetName(), null);
            InstallerExit exit;
            Optional<InstalledApplication> detectedInstallation = Optional.empty();
            if (preview.packageType() == PackageType.ZIP) {
                new ManagedZipInstaller(paths.programDirectory().getParent().getParent().resolve("AppFleetManaged")).install(installer.path(), id, cancellation);
                exit = InstallerExit.forGeneric(0);
            } else {
                List<String> arguments = snapshot.manifest() == null ? List.of() : snapshot.manifest().installer().silentArgs();
                journal.write(id.slug(), "Запуск установщика", "Начата", "Запускается " + preview.assetName() + (arguments.isEmpty() ? "" : " с параметрами " + String.join(" ", arguments)), null);
                exit = new ExternalInstallerRunner().run(installer.path(), preview.packageType(), arguments);
                journal.write(id.slug(), "Запуск установщика", "Завершён", "Установщик завершил основной процесс с кодом " + exit.code(), null);
                if (!exit.successful()) throw new IOException(exit.message());
                if (snapshot.manifest() != null && snapshot.manifest().detection() != null) {
                    detectedInstallation = Optional.of(new StandardInstallationAwaiter(registry).await(snapshot.manifest().appId(), snapshot.release().tagName(), Duration.ofSeconds(60)));
                }
            }
            restartPreviouslyRunning(snapshot, previouslyRunning);
            RepositoryState installed = withInstalled(snapshot.persisted(), snapshot, preview.packageType(), detectedInstallation);
            synchronizedUpdateRepositories(repositories.repositories().stream().map(existing -> same(existing, id) ? installed : existing).toList());
            ApplicationSnapshot updated = check(installed);
            synchronized (this) { snapshots.put(id.normalizedKey(), updated); }
            if (settings.deleteInstallerAfterSuccess()) deleteOperationDirectory(operation);
            String resultMessage = exit.message();
            if (signature == AuthenticodeStatus.NOT_SIGNED) {
                String warning = "Установщик не имеет цифровой подписи. AppFleet проверил SHA-256 и продолжил установку.";
                journal.write(id.slug(), "Проверка файла", "Предупреждение", warning, null);
                resultMessage += "\n\nПредупреждение: " + warning;
            }
            journal.write(id.slug(), "Установка", "Успешно", resultMessage, null);
            return new OperationResult(true, exit.restartRequired(), resultMessage, updated);
        } catch (OperationCancelledException cancelled) {
            journal.write(id.slug(), "Установка", "Отменена", "Операция отменена пользователем", null);
            return new OperationResult(false, false, "Операция отменена", snapshot);
        } catch (Exception failure) {
            journal.write(id.slug(), "Установка", "Ошибка", "Установка не выполнена: " + failure.getMessage(), failure);
            return new OperationResult(false, false, "Установка не выполнена: " + failure.getMessage(), snapshot);
        } finally { if (!settings.deleteInstallerAfterSuccess()) { /* temporary installer intentionally retained for user diagnostics */ } }
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
    private List<RunningApplication> handleRunningProcesses(ApplicationSnapshot snapshot, OperationRequest request) throws IOException {
        Set<String> names = snapshot.manifest() != null ? new LinkedHashSet<>(snapshot.manifest().processNames()) : snapshot.persisted().processNames();
        if (names.isEmpty()) return List.of();
        ProcessManager processes = new ProcessManager();
        List<RunningApplication> running = processes.findByExactNames(names);
        if (running.isEmpty()) return List.of();
        if (!request.closeRunningApplications()) throw new IOException("Приложение запущено; выберите «Закрыть автоматически» или отмените операцию");
        for (RunningApplication process : running) {
            boolean closed = processes.requestGracefulClose(process, java.time.Duration.ofSeconds(10));
            if (!closed && !request.forceCloseIfNeeded()) throw new IOException("Приложение не закрылось штатно; требуется отдельное подтверждение принудительного завершения");
            if (!closed && !processes.forceCloseAfterExplicitConsent(process, java.time.Duration.ofSeconds(5))) throw new IOException("Не удалось закрыть запущенное приложение");
            journal.write(snapshot.repository().slug(), "Закрытие приложения", "Успешно", "Закрыт процесс " + process.pid(), null);
        }
        return running;
    }
    private void restartPreviouslyRunning(ApplicationSnapshot snapshot, List<RunningApplication> previouslyRunning) {
        if (!settings.restartPreviouslyRunningApp()) return;
        for (RunningApplication process : previouslyRunning) {
            try {
                new ProcessBuilder(process.command()).start();
                journal.write(snapshot.repository().slug(), "Перезапуск приложения", "Успешно", "Повторно запущен " + process.command(), null);
            } catch (IOException failure) {
                journal.write(snapshot.repository().slug(), "Перезапуск приложения", "Ошибка", "Не удалось повторно запустить приложение", failure);
            }
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
    private ApplicationSnapshot snapshot(RepositoryId id, RepositoryState state, AppStatus status, GithubRelease release, ReleaseAsset asset, List<ReleaseAsset> candidates, AppFleetManifest manifest, String message) {
        return new ApplicationSnapshot(id, state, status, manifest == null ? id.repository() : manifest.name(), installedVersion(state, manifest), release, asset, candidates, manifest, message);
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
    private static void deleteOperationDirectory(Path operation) { try { if (!Files.exists(operation)) return; try (var walk = Files.walk(operation)) { walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } } catch (IOException ignored) { } }
    @Override public void close() { worker.shutdownNow(); }
    private record ManifestResult(AppFleetManifest manifest, AssetSelection selection, String failure) { }
}
