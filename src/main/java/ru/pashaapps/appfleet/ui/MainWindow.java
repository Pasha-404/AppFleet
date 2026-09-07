package ru.pashaapps.appfleet.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import ru.pashaapps.appfleet.BuildInfo;
import ru.pashaapps.appfleet.domain.ReleaseAsset;
import ru.pashaapps.appfleet.domain.RepositoryId;
import ru.pashaapps.appfleet.install.CancellationToken;
import ru.pashaapps.appfleet.install.CoalescingDownloadProgress;
import ru.pashaapps.appfleet.install.DownloadProgress;
import ru.pashaapps.appfleet.persistence.OperationEntry;
import ru.pashaapps.appfleet.persistence.UserSettings;
import ru.pashaapps.appfleet.service.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** The fixed sidebar, application cards and secondary journal/settings pages. */
public final class MainWindow {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final Stage stage;
    private final AppFleetService service;
    private final SelfUpdateService selfUpdate;
    private final BuildInfo build;
    private final ExecutorService startupWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("appfleet-startup-", 0).factory());
    private final ObservableList<ApplicationSnapshot> applications = FXCollections.observableArrayList();
    private final FlowPane applicationCards = new FlowPane(16, 16);
    private final ScrollPane applicationScroll = new ScrollPane(applicationCards);
    private final InstalledApplicationIconResolver icons = new InstalledApplicationIconResolver();
    private final TableView<OperationEntry> journalTable = new TableView<>();
    private final Label statusLine = new Label("Проверка ещё не выполнялась");
    private final StackPane content = new StackPane();
    private final Node applicationsPage;
    private final Node journalPage;
    private final Node settingsPage;

    public MainWindow(Stage stage, AppFleetService service, SelfUpdateService selfUpdate, BuildInfo build) {
        this.stage = stage;
        this.service = service;
        this.selfUpdate = selfUpdate;
        this.build = build;
        this.applicationsPage = applicationsPage();
        this.journalPage = journalPage();
        this.settingsPage = settingsPage();
        applications.addListener((ListChangeListener<ApplicationSnapshot>) change -> rebuildApplicationCards());
    }
    public void show() {
        BorderPane root = new BorderPane();
        root.setTop(titleBar());
        root.setLeft(sidebar());
        content.getChildren().setAll(applicationsPage);
        root.setCenter(content);
        var scene = new javafx.scene.Scene(root, 1280, 760);
        String stylesheet = getClass().getResource(WindowsTheme.isDark() ? "/appfleet-dark.css" : "/appfleet-light.css").toExternalForm();
        scene.getStylesheets().add(stylesheet);
        stage.setTitle("AppFleet");
        stage.setMinWidth(760); stage.setMinHeight(600); stage.setScene(scene); stage.show();
        startInitialSequence();
    }
    public void close() { startupWorker.shutdownNow(); icons.close(); service.close(); }

    private Node titleBar() {
        Label title = new Label("AppFleet"); title.getStyleClass().add("app-title");
        HBox bar = new HBox(title); bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(12, 20, 12, 20)); bar.getStyleClass().add("title-bar");
        return bar;
    }
    private Node sidebar() {
        Button apps = navigationButton("Приложения", () -> content.getChildren().setAll(applicationsPage));
        Button journal = navigationButton("Журнал", () -> { refreshJournal(); content.getChildren().setAll(journalPage); });
        Button settings = navigationButton("Настройки", () -> content.getChildren().setAll(settingsPage));
        Button check = sidebarActionButton("Проверить", this::refreshApplications);
        Button add = sidebarActionButton("Добавить репозиторий", this::addRepository); add.getStyleClass().add("primary");
        statusLine.setWrapText(true);
        statusLine.getStyleClass().add("muted");
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        Label version = new Label("Версия AppFleet: " + build.version()); version.setWrapText(true); version.getStyleClass().add("muted");
        VBox box = new VBox(8, apps, journal, settings, new Separator(), check, add, statusLine, spacer, new Separator(), version);
        box.setPrefWidth(255); box.setMinWidth(220); box.setPadding(new Insets(18, 14, 18, 14)); box.getStyleClass().add("sidebar");
        return box;
    }
    private Button navigationButton(String text, Runnable action) { Button button = new Button(text); button.setMaxWidth(Double.MAX_VALUE); button.getStyleClass().add("navigation"); button.setOnAction(event -> action.run()); return button; }
    private Button sidebarActionButton(String text, Runnable action) { Button button = new Button(text); button.setMaxWidth(Double.MAX_VALUE); button.setOnAction(event -> action.run()); return button; }
    private Node applicationsPage() {
        Label heading = new Label("Приложения"); heading.getStyleClass().add("page-heading");
        Label description = new Label("Устанавливайте и обновляйте приложения прямо из карточек. Подробные сведения доступны по кнопке «Подробнее». "); description.setWrapText(true); description.getStyleClass().add("muted");
        VBox header = new VBox(4, heading, description); header.getStyleClass().add("page-header");
        applicationCards.setAlignment(Pos.TOP_LEFT);
        applicationCards.getStyleClass().add("application-cards");
        applicationScroll.setFitToWidth(true);
        applicationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        applicationScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        applicationScroll.getStyleClass().add("applications-scroll");
        VBox page = new VBox(header, applicationScroll); VBox.setVgrow(applicationScroll, Priority.ALWAYS); page.getStyleClass().addAll("content-page", "applications-page");
        return page;
    }
    private void rebuildApplicationCards() {
        applicationCards.getChildren().clear();
        if (applications.isEmpty()) {
            Label empty = new Label("Добавьте публичный GitHub-репозиторий, чтобы начать."); empty.setWrapText(true); empty.getStyleClass().add("empty-applications");
            applicationCards.getChildren().add(empty);
            return;
        }
        applications.forEach(snapshot -> applicationCards.getChildren().add(applicationCard(snapshot)));
    }
    private Node applicationCard(ApplicationSnapshot snapshot) {
        Node icon = icons.iconFor(snapshot);
        Label name = new Label(snapshot.displayName()); name.setWrapText(true); name.getStyleClass().add("app-card-title");
        Label repository = new Label(snapshot.repository().slug()); repository.getStyleClass().add("muted");
        VBox identity = new VBox(2, name, repository); HBox.setHgrow(identity, Priority.ALWAYS);
        CardAction presentation = cardAction(snapshot.status());
        Button action = new Button(presentation.label()); action.setDisable(!presentation.enabled()); action.setOnAction(event -> actOn(snapshot)); action.getStyleClass().add("primary");
        MenuButton menu = applicationMenu(snapshot); menu.setTooltip(new Tooltip("Дополнительные действия"));
        HBox header = new HBox(12, icon, identity); if (presentation.enabled()) header.getChildren().add(action); header.getChildren().add(menu); header.setAlignment(Pos.CENTER_LEFT);
        Label status = new Label(snapshot.status().display()); status.getStyleClass().addAll("status-chip", statusStyle(snapshot.status()));
        Label versions = new Label(versionSummary(snapshot)); versions.setWrapText(true); versions.getStyleClass().add("card-summary");
        Button details = new Button("Подробнее"); details.setOnAction(event -> showApplicationDetails(snapshot)); details.getStyleClass().add("secondary");
        HBox footer = new HBox(details); footer.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(14, header, status, versions, footer); card.setMinWidth(280); card.setPrefWidth(360); card.setMaxWidth(420); card.getStyleClass().add("app-card");
        return card;
    }
    private MenuButton applicationMenu(ApplicationSnapshot snapshot) {
        MenuItem details = new MenuItem("Подробнее"); details.setOnAction(event -> showApplicationDetails(snapshot));
        MenuItem choose = new MenuItem("Выбрать файл релиза"); choose.setDisable(snapshot.release() == null); choose.setOnAction(event -> chooseAsset(snapshot));
        MenuItem remove = new MenuItem("Удалить из списка"); remove.setOnAction(event -> removeRepository(snapshot));
        MenuButton menu = new MenuButton("⋯", null, details, choose, new SeparatorMenuItem(), remove); menu.getStyleClass().add("card-menu");
        return menu;
    }
    static CardAction cardAction(AppStatus status) {
        return switch (status) {
            case NOT_INSTALLED -> new CardAction("Установить", true);
            case UPDATE_AVAILABLE -> new CardAction("Обновить", true);
            case CHECK_ERROR -> new CardAction("Повторить", true);
            case UP_TO_DATE -> new CardAction("Актуально", false);
            default -> new CardAction("Недоступно", false);
        };
    }
    private static String statusStyle(AppStatus status) {
        return switch (status) {
            case UP_TO_DATE -> "status-ok";
            case UPDATE_AVAILABLE, NOT_INSTALLED -> "status-attention";
            case CHECK_ERROR -> "status-error";
            default -> "status-neutral";
        };
    }
    private static String versionSummary(ApplicationSnapshot snapshot) {
        if (snapshot.release() == null) return snapshot.message() == null || snapshot.message().isBlank() ? "Сведения о релизе пока недоступны" : snapshot.message();
        String installed = emptyToDash(snapshot.installedVersion());
        return "Установлена: " + installed + "\nПоследняя: " + snapshot.latestVersion();
    }
    private void showApplicationDetails(ApplicationSnapshot snapshot) {
        String releaseDate = snapshot.release() == null ? "—" : DATE_TIME.format(snapshot.release().publishedAt());
        String size = snapshot.selectedAsset() == null ? "—" : humanSize(snapshot.selectedAsset().size());
        String executable = snapshot.persisted().executable() == null ? "—" : snapshot.persisted().executable();
        String text = "Состояние: " + snapshot.status().display()
                + "\nУстановленная версия: " + emptyToDash(snapshot.installedVersion())
                + "\nПоследняя версия: " + snapshot.latestVersion()
                + "\nДата релиза: " + releaseDate
                + "\nФайл: " + snapshot.assetName()
                + "\nРазмер: " + size
                + "\nИсполняемый файл: " + executable
                + "\nРепозиторий: " + snapshot.repository().canonicalUrl()
                + "\nManifest: " + (snapshot.manifest() == null ? "не опубликован или не принят" : "проверен")
                + "\n\n" + (snapshot.message() == null ? "" : snapshot.message());
        TextArea details = new TextArea(text); details.setEditable(false); details.setWrapText(true); details.setPrefColumnCount(64); details.setPrefRowCount(14);
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(snapshot.displayName()); dialog.setHeaderText("Сведения о приложении"); dialog.getDialogPane().setContent(details); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }
    record CardAction(String label, boolean enabled) { }
    private Node journalPage() {
        Label heading = new Label("Журнал"); heading.getStyleClass().add("page-heading");
        Button refresh = new Button("Обновить"); refresh.setOnAction(event -> refreshJournal());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox commands = new HBox(12, heading, spacer, refresh); commands.setAlignment(Pos.CENTER_LEFT); commands.setPadding(new Insets(20, 24, 12, 24)); commands.getStyleClass().add("command-bar");
        journalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        journalTable.getColumns().setAll(List.of(journalColumn("Дата и время", 155, entry -> DATE_TIME.format(entry.occurredAt())), journalColumn("Приложение", 170, OperationEntry::repository), journalColumn("Операция", 180, OperationEntry::operation), journalColumn("Результат", 125, OperationEntry::result), journalColumn("Описание", 390, OperationEntry::message)));
        journalTable.setRowFactory(ignored -> { TableRow<OperationEntry> row = new TableRow<>(); row.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && !row.isEmpty()) showTechnicalDetails(row.getItem()); }); return row; });
        VBox page = new VBox(commands, journalTable); VBox.setVgrow(journalTable, Priority.ALWAYS); page.getStyleClass().add("content-page"); return page;
    }
    private TableColumn<OperationEntry, String> journalColumn(String title, double width, Function<OperationEntry, String> mapper) { TableColumn<OperationEntry, String> column = new TableColumn<>(title); column.setPrefWidth(width); column.setCellValueFactory(data -> new ReadOnlyStringWrapper(mapper.apply(data.getValue()))); return column; }
    private Node settingsPage() {
        Label heading = new Label("Настройки"); heading.getStyleClass().add("page-heading");
        Label description = new Label("Настройки применяются к следующим операциям установки и обновления."); description.setWrapText(true); description.getStyleClass().add("muted");
        CheckBox restart = new CheckBox("Повторно запускать приложение после обновления, если оно было запущено"); restart.setWrapText(true); restart.setSelected(service.settings().restartPreviouslyRunningApp());
        CheckBox delete = new CheckBox("Удалять скачанные установщики после успешной установки"); delete.setWrapText(true); delete.setSelected(service.settings().deleteInstallerAfterSuccess());
        CheckBox desktopShortcut = new CheckBox("Создавать ярлык на рабочем столе при первой установке"); desktopShortcut.setWrapText(true); desktopShortcut.setSelected(service.settings().createDesktopShortcutForNewApplications());
        restart.selectedProperty().addListener((observable, wasSelected, selected) -> saveSettings(new UserSettings(selected, delete.isSelected(), desktopShortcut.isSelected())));
        delete.selectedProperty().addListener((observable, wasSelected, selected) -> saveSettings(new UserSettings(restart.isSelected(), selected, desktopShortcut.isSelected())));
        desktopShortcut.selectedProperty().addListener((observable, wasSelected, selected) -> saveSettings(new UserSettings(restart.isSelected(), delete.isSelected(), selected)));
        VBox options = new VBox(14, restart, delete, desktopShortcut); options.getStyleClass().add("settings-card");
        VBox page = new VBox(18, heading, description, options); page.getStyleClass().addAll("content-page", "settings-page");
        return page;
    }
    private void startInitialSequence() {
        selfUpdate.recoverAfterLaunch();
        service.record("AppFleet", "Запуск", "Успешно", "Запущена версия " + build.version(), null);
        selfUpdate.checkAsync(startupWorker).whenComplete((offer, failure) -> Platform.runLater(() -> {
            if (failure != null) { service.record("AppFleet", "Проверка обновления AppFleet", "Ошибка", "Не удалось проверить обновление AppFleet", unwrap(failure)); refreshApplications(); return; }
            if (offer.isEmpty()) { service.record("AppFleet", "Проверка обновления AppFleet", "Успешно", "AppFleet актуален", null); refreshApplications(); return; }
            service.record("AppFleet", "Проверка обновления AppFleet", "Доступно обновление", "Доступна версия " + offer.get().manifest().version(), null);
            confirmSelfUpdate(offer.get());
        }));
    }
    private void confirmSelfUpdate(SelfUpdateOffer offer) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION); dialog.initOwner(stage); dialog.setTitle("Обновление AppFleet"); dialog.setHeaderText("Доступна версия " + offer.manifest().version()); dialog.setContentText("Файл: " + offer.installer().name() + " (" + humanSize(offer.installer().size()) + ")\n\n" + offer.release().body() + "\n\nИсточник: " + offer.release().htmlUrl());
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) { service.record("AppFleet", "Самообновление", "Отменено", "Пользователь отказался от обновления", null); refreshApplications(); return; }
        AtomicBoolean cancelled = new AtomicBoolean(); Stage progress = progressStage("Скачивание обновления AppFleet", cancelled);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return selfUpdate.install(offer, cancelled::get, updateProgress(progress), operationProgress(progress)); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); } }, startupWorker).whenComplete((started, failure) -> Platform.runLater(() -> { progress.close(); if (failure != null) { service.record("AppFleet", "Самообновление", "Ошибка", "Не удалось запустить обновление", unwrap(failure)); showError("Обновление AppFleet не запущено", unwrap(failure).getMessage()); refreshApplications(); } else { service.record("AppFleet", "Самообновление", "Запущено", "Внешний установщик запущен; AppFleet будет перезапущен", null); Platform.exit(); } }));
    }
    private void refreshApplications() {
        statusLine.setText("Проверка репозиториев…");
        service.checkAll().whenComplete((checked, failure) -> Platform.runLater(() -> { if (failure != null) { statusLine.setText("Ошибка проверки"); showError("Не удалось проверить репозитории", unwrap(failure).getMessage()); return; } applications.setAll(checked); statusLine.setText("Последняя проверка: " + DATE_TIME.format(java.time.Instant.now()) + " · репозиториев: " + checked.size()); }));
    }
    private void addRepository() {
        TextInputDialog dialog = new TextInputDialog(); dialog.initOwner(stage); dialog.setTitle("Добавить репозиторий"); dialog.setHeaderText("Публичный GitHub-репозиторий"); dialog.setContentText("Ссылка:");
        dialog.showAndWait().filter(value -> !value.isBlank()).ifPresent(url -> service.addRepository(url).whenComplete((added, failure) -> Platform.runLater(() -> { if (failure != null) showError("Репозиторий не добавлен", unwrap(failure).getMessage()); else { applications.setAll(service.currentSnapshots()); statusLine.setText("Репозиторий добавлен: " + added.repository().slug()); if (!added.choiceCandidates().isEmpty()) chooseAsset(added); } })));
    }
    private void chooseAsset(ApplicationSnapshot snapshot) {
        ListView<ReleaseAsset> list = new ListView<>(FXCollections.observableArrayList(assetCandidates(snapshot))); list.setCellFactory(ignored -> new ListCell<>() { @Override protected void updateItem(ReleaseAsset item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.name() + " · " + item.packageType() + " · " + item.architecture() + " · " + humanSize(item.size())); } });
        Dialog<ReleaseAsset> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Выберите файл релиза"); dialog.setHeaderText(snapshot.repository().slug()); dialog.getDialogPane().setContent(list); ButtonType choose = new ButtonType("Выбрать", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(choose, ButtonType.CANCEL); dialog.setResultConverter(button -> button == choose ? list.getSelectionModel().getSelectedItem() : null); dialog.showAndWait().ifPresent(asset -> service.chooseAsset(snapshot.repository(), asset).whenComplete((updated, failure) -> Platform.runLater(() -> { if (failure != null) showError("Не удалось сохранить выбор", unwrap(failure).getMessage()); else applications.setAll(service.currentSnapshots()); })));
    }
    private void removeRepository(ApplicationSnapshot snapshot) { Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Приложение будет удалено только из списка AppFleet. Установленная программа останется в Windows.", ButtonType.OK, ButtonType.CANCEL); confirm.initOwner(stage); confirm.setHeaderText("Удалить " + snapshot.repository().slug() + " из списка?"); if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) service.removeRepository(snapshot.repository()).whenComplete((nothing, failure) -> Platform.runLater(() -> { if (failure != null) showError("Не удалось удалить запись", unwrap(failure).getMessage()); else applications.setAll(service.currentSnapshots()); })); }
    private void actOn(ApplicationSnapshot snapshot) {
        if (snapshot.status() == AppStatus.CHECK_ERROR) {
            refreshApplications();
            return;
        }
        if (isAppFleetRepository(snapshot)) {
            checkSelfUpdateFromCard();
            return;
        }
        OperationPreview preview;
        try {
            preview = service.preview(snapshot.repository());
        } catch (Exception failure) {
            showError("Операция недоступна", failure.getMessage());
            return;
        }
        showOperationConfirmation(preview).ifPresent(request -> prepareAndRun(snapshot, request));
    }

    private boolean isAppFleetRepository(ApplicationSnapshot snapshot) {
        return snapshot.repository().normalizedKey().equals(RepositoryId.fromGithubUrl(build.repositoryUrl()).normalizedKey());
    }

    private void checkSelfUpdateFromCard() {
        selfUpdate.checkAsync(startupWorker).whenComplete((offer, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                showError("Не удалось проверить обновление AppFleet", unwrap(failure).getMessage());
                return;
            }
            if (offer.isEmpty()) {
                showInfo("AppFleet актуален", "Для запущенной версии обновлений не найдено.");
                refreshApplications();
                return;
            }
            confirmSelfUpdate(offer.get());
        }));
    }

    private void prepareAndRun(ApplicationSnapshot snapshot, OperationRequest request) {
        try {
            runOperation(service.prepareInstallation(snapshot.repository(), request));
        } catch (Exception failure) {
            showError("Операция недоступна", failure.getMessage());
        }
    }

    private void runOperation(InstallationPlan plan) {
        AtomicBoolean cancelled = new AtomicBoolean(); Stage progress = progressStage("Скачивание и установка", cancelled);
        service.installOrUpdate(plan, cancelled::get, updateProgress(progress), operationProgress(progress)).whenComplete((result, failure) -> Platform.runLater(() -> {
            progress.close();
            if (failure != null) { showError("Операция завершилась ошибкой", unwrap(failure).getMessage()); return; }
            if (result.requiresForceCloseConfirmation()) {
                Alert force = new Alert(Alert.AlertType.CONFIRMATION, "Приложение не завершилось после штатного запроса закрытия. Разрешить принудительно завершить только ранее обнаруженные процессы и продолжить установку?", ButtonType.OK, ButtonType.CANCEL);
                force.initOwner(stage); force.setHeaderText("Требуется отдельное подтверждение");
                continueAfterForceClose(result.forceCloseContinuation(), force.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK);
                return;
            }
            replaceApplicationsAfterInstallation();
            Alert outcome = new Alert(result.successful() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR, result.message(), ButtonType.OK); outcome.initOwner(stage); outcome.setHeaderText(result.successful() ? "Операция завершена" : "Операция не выполнена"); outcome.showAndWait();
        }));
    }

    private void continueAfterForceClose(ForceCloseContinuation continuation, boolean accepted) {
        if (!accepted) {
            service.continueAfterForceClose(continuation, false, OperationProgress.NONE);
            return;
        }
        AtomicBoolean ignoredCancellation = new AtomicBoolean();
        Stage progress = progressStage("Завершение установки", ignoredCancellation);
        showOperationPhase(progress, OperationPhase.LAUNCHING_INSTALLER);
        service.continueAfterForceClose(continuation, true, operationProgress(progress)).whenComplete((result, failure) -> Platform.runLater(() -> {
            progress.close();
            if (failure != null) {
                showError("Операция завершилась ошибкой", unwrap(failure).getMessage());
                return;
            }
            replaceApplicationsAfterInstallation();
            Alert outcome = new Alert(result.successful() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR, result.message(), ButtonType.OK);
            outcome.initOwner(stage);
            outcome.setHeaderText(result.successful() ? "Операция завершена" : "Операция не выполнена");
            outcome.showAndWait();
        }));
    }

    private void replaceApplicationsAfterInstallation() {
        List<ApplicationSnapshot> refreshed = service.currentSnapshots();
        refreshed.forEach(icons::invalidate);
        applications.setAll(refreshed);
    }

    private Optional<OperationRequest> showOperationConfirmation(OperationPreview preview) {
        Dialog<OperationRequest> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Подтверждение установки"); dialog.setHeaderText(preview.application().displayName() + " — " + preview.targetVersion());
        String signatureNotice = preview.packageType() == ru.pashaapps.appfleet.domain.PackageType.EXE
                ? "\nЦифровая подпись: будет проверена; отсутствие подписи не блокирует установку."
                : "";
        Label details = new Label("Текущая версия: " + emptyToDash(preview.currentVersion()) + "\nУстанавливаемая версия: " + preview.targetVersion() + "\nФайл: " + preview.assetName() + " (" + humanSize(preview.assetSize()) + ")\nИсточник: " + preview.sourceUrl() + "\nSHA-256: " + (preview.sha256Available() ? "будет проверен" : "не опубликован") + signatureNotice + "\n\n" + preview.releaseDescription() + (preview.thirdPartyInteractiveExeWarning() ? "\n\nВнимание: сторонний EXE будет запущен интерактивно." : "")); details.setWrapText(true);
        CheckBox close = new CheckBox("Закрыть запущенное приложение автоматически, если оно обнаружено"); close.setWrapText(true);
        VBox content = new VBox(12, details, new Separator(), close); content.setPrefWidth(600); dialog.getDialogPane().setContent(content); ButtonType execute = new ButtonType(preview.currentVersion() == null ? "Установить" : "Обновить", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(execute, ButtonType.CANCEL); dialog.setResultConverter(button -> button == execute ? new OperationRequest(true, close.isSelected(), false) : OperationRequest.cancelled()); return dialog.showAndWait().filter(OperationRequest::confirmed);
    }
    private Stage progressStage(String title, AtomicBoolean cancelled) { Stage popup = new Stage(); popup.initOwner(stage); popup.initModality(Modality.WINDOW_MODAL); popup.setTitle(title); ProgressBar bar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS); bar.setPrefWidth(330); Label label = new Label("Подготовка…"); Button cancel = new Button("Отмена"); cancel.setOnAction(event -> { cancelled.set(true); cancel.setDisable(true); label.setText("Отмена…"); }); VBox box = new VBox(12, label, bar, cancel); box.setPadding(new Insets(20)); box.setAlignment(Pos.CENTER); popup.setScene(new javafx.scene.Scene(box)); popup.setResizable(false); popup.show(); popup.getProperties().put("bar", bar); popup.getProperties().put("label", label); popup.getProperties().put("cancel", cancel); return popup; }
    private DownloadProgress updateProgress(Stage popup) {
        return new CoalescingDownloadProgress((received, total) -> Platform.runLater(() -> {
            ProgressBar bar = (ProgressBar) popup.getProperties().get("bar");
            Label label = (Label) popup.getProperties().get("label");
            bar.setProgress(total <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : (double) received / total);
            label.setText(total <= 0 ? humanSize(received) + " скачано" : humanSize(received) + " из " + humanSize(total));
        }));
    }
    private OperationProgress operationProgress(Stage popup) { return phase -> Platform.runLater(() -> showOperationPhase(popup, phase)); }
    private void showOperationPhase(Stage popup, OperationPhase phase) { Label label = (Label) popup.getProperties().get("label"); Button cancel = (Button) popup.getProperties().get("cancel"); if (phase != OperationPhase.FINISHED) label.setText(phase.display()); cancel.setDisable(!phase.cancellable() || cancel.isDisable()); }
    private void refreshJournal() { journalTable.setItems(FXCollections.observableArrayList(service.journalEntries())); }
    private void showTechnicalDetails(OperationEntry entry) { Alert detail = new Alert(Alert.AlertType.INFORMATION); detail.initOwner(stage); detail.setTitle("Технические подробности"); detail.setHeaderText(entry.operation() + " — " + entry.result()); TextArea area = new TextArea(entry.technicalDetails().isBlank() ? entry.message() : entry.technicalDetails()); area.setEditable(false); area.setWrapText(false); area.setPrefColumnCount(90); area.setPrefRowCount(20); detail.getDialogPane().setContent(area); detail.showAndWait(); }
    private static List<ReleaseAsset> assetCandidates(ApplicationSnapshot snapshot) {
        if (!snapshot.choiceCandidates().isEmpty()) return snapshot.choiceCandidates();
        if (snapshot.release() == null) return List.of();
        return new ru.pashaapps.appfleet.domain.AssetSelector().eligibleAssets(snapshot.release());
    }
    private void saveSettings(UserSettings settings) { try { service.saveSettings(settings); } catch (Exception failure) { showError("Не удалось сохранить настройки", failure.getMessage()); } }
    private void showError(String title, String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Неизвестная ошибка" : message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText(title); alert.showAndWait(); }
    private void showInfo(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText(title); alert.showAndWait(); }
    private static String emptyToDash(String value) { return value == null || value.isBlank() ? "Не установлено" : value; }
    private static String humanSize(long bytes) { if (bytes < 1024) return bytes + " Б"; if (bytes < 1024L * 1024) return String.format("%.1f КБ", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / 1024.0 / 1024.0); return String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0); }
    private static Throwable unwrap(Throwable failure) { return failure.getCause() == null ? failure : failure.getCause(); }
}
