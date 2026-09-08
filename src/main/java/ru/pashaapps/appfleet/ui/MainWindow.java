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
    private final boolean developmentRun;
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
    private final ToggleGroup navigation = new ToggleGroup();
    private Button checkUpdatesButton;

    public MainWindow(Stage stage, AppFleetService service, SelfUpdateService selfUpdate, BuildInfo build, boolean developmentRun) {
        this.stage = stage;
        this.service = service;
        this.selfUpdate = selfUpdate;
        this.build = build;
        this.developmentRun = developmentRun;
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
        ToggleButton apps = navigationButton("Приложения", () -> content.getChildren().setAll(applicationsPage));
        ToggleButton journal = navigationButton("Журнал", () -> { refreshJournal(); content.getChildren().setAll(journalPage); });
        ToggleButton settings = navigationButton("Настройки", () -> content.getChildren().setAll(settingsPage));
        apps.setSelected(true);
        Button check = sidebarActionButton("Проверить обновления", this::refreshApplications);
        checkUpdatesButton = check;
        Button add = sidebarActionButton("Добавить репозиторий", this::addRepository); add.getStyleClass().add("primary");
        statusLine.setWrapText(true);
        statusLine.getStyleClass().add("muted");
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        Label version = new Label("Версия AppFleet: " + build.version()); version.setWrapText(true); version.getStyleClass().add("muted");
        VBox box = new VBox(8, apps, journal, settings, new Separator(), check, add, statusLine, spacer, new Separator(), version);
        box.setPrefWidth(255); box.setMinWidth(220); box.setPadding(new Insets(18, 14, 18, 14)); box.getStyleClass().add("sidebar");
        return box;
    }
    private ToggleButton navigationButton(String text, Runnable action) { ToggleButton button = new ToggleButton(text); button.setMaxWidth(Double.MAX_VALUE); button.setToggleGroup(navigation); button.getStyleClass().add("navigation"); button.setOnAction(event -> action.run()); return button; }
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
        MenuButton menu = applicationMenu(snapshot); menu.setTooltip(new Tooltip("Действия с " + snapshot.displayName())); menu.setAccessibleText("Действия с " + snapshot.displayName());
        HBox header = new HBox(12, icon, identity, menu); header.setAlignment(Pos.CENTER_LEFT);
        Label status = new Label(snapshot.status().display()); status.getStyleClass().addAll("status-chip", statusStyle(snapshot.status()));
        Label versions = new Label(versionSummary(snapshot)); versions.setWrapText(true); versions.getStyleClass().add("card-summary");
        Label freshness = new Label(freshnessWarning(snapshot)); freshness.setWrapText(true); freshness.getStyleClass().add("card-freshness-warning"); freshness.setVisible(!freshness.getText().isBlank()); freshness.setManaged(!freshness.getText().isBlank());
        Button details = new Button("Подробнее"); details.setOnAction(event -> showApplicationDetails(snapshot)); details.getStyleClass().add("secondary");
        FlowPane footer = new FlowPane(8, 8); footer.setAlignment(Pos.CENTER_LEFT); footer.getStyleClass().add("app-card-footer");
        if (presentation.enabled()) {
            Button action = new Button(presentation.label()); action.setOnAction(event -> performCardAction(snapshot, presentation)); action.getStyleClass().add("primary");
            footer.getChildren().add(action);
        }
        footer.getChildren().add(details);
        VBox card = new VBox(14, header, status, versions, freshness, footer); card.setMinWidth(320); card.setPrefWidth(360); card.setMaxWidth(420); card.getStyleClass().add("app-card");
        return card;
    }
    private MenuButton applicationMenu(ApplicationSnapshot snapshot) {
        MenuItem remove = new MenuItem("Удалить из списка…"); remove.setOnAction(event -> removeRepository(snapshot));
        MenuButton menu = new MenuButton("⋯");
        if (manualAssetChoiceAvailable(snapshot)) {
            MenuItem choose = new MenuItem("Выбрать файл релиза…"); choose.setOnAction(event -> chooseAsset(snapshot));
            menu.getItems().addAll(choose, new SeparatorMenuItem());
        }
        menu.getItems().add(remove); menu.getStyleClass().add("card-menu");
        return menu;
    }
    static CardAction cardAction(AppStatus status) {
        return switch (status) {
            case NOT_INSTALLED -> new CardAction("Установить", true, CardActionKind.INSTALL_OR_UPDATE);
            case UPDATE_AVAILABLE -> new CardAction("Обновить", true, CardActionKind.INSTALL_OR_UPDATE);
            case ASSET_SELECTION_REQUIRED -> new CardAction("Выбрать файл…", true, CardActionKind.CHOOSE_ASSET);
            case CHECK_ERROR -> new CardAction("Повторить проверку", true, CardActionKind.RETRY_CHECK);
            default -> new CardAction("", false, CardActionKind.NONE);
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
        if (snapshot.release() == null) {
            if (snapshot.status() == AppStatus.CHECK_ERROR) return "Сведения о последнем релизе недоступны.";
            return snapshot.message() == null || snapshot.message().isBlank() ? "Сведения о релизе пока недоступны" : snapshot.message();
        }
        String installed = emptyToDash(snapshot.installedVersion());
        return "Установлена: " + installed + "\nПоследняя: " + snapshot.latestVersion();
    }
    static String freshnessWarning(ApplicationSnapshot snapshot) {
        return freshnessWarning(snapshot.status(), snapshot.message());
    }
    static String freshnessWarning(AppStatus status, String message) {
        if (status == AppStatus.CHECK_ERROR) return "Не удалось проверить обновления. Повторите проверку.";
        if (message != null && message.contains("Показаны последние подтверждённые данные")) {
            return "Не удалось проверить обновления. Показаны сохранённые данные.";
        }
        return "";
    }
    static boolean manualAssetChoiceAvailable(ApplicationSnapshot snapshot) {
        return snapshot.manifest() == null && snapshot.release() != null && !assetCandidates(snapshot).isEmpty();
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
    private void performCardAction(ApplicationSnapshot snapshot, CardAction action) {
        switch (action.kind()) {
            case RETRY_CHECK -> refreshApplications();
            case CHOOSE_ASSET -> chooseAsset(snapshot);
            case INSTALL_OR_UPDATE -> actOn(snapshot);
            case NONE -> { }
        }
    }
    enum CardActionKind { INSTALL_OR_UPDATE, CHOOSE_ASSET, RETRY_CHECK, NONE }
    record CardAction(String label, boolean enabled, CardActionKind kind) { }
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
        Label description = new Label("Настройки применяются к следующим операциям установки и обновления и сохраняются автоматически."); description.setWrapText(true); description.getStyleClass().add("muted");
        CheckBox restart = new CheckBox("Открывать приложение после обновления"); restart.setSelected(service.settings().restartPreviouslyRunningApp());
        CheckBox delete = new CheckBox("Удалять скачанные файлы после установки"); delete.setSelected(service.settings().deleteInstallerAfterSuccess());
        CheckBox desktopShortcut = new CheckBox("Создавать ярлык на рабочем столе"); desktopShortcut.setSelected(service.settings().createDesktopShortcutForNewApplications());
        AtomicBoolean restoring = new AtomicBoolean();
        restart.selectedProperty().addListener((observable, wasSelected, selected) -> persistSettings(restart, delete, desktopShortcut, restoring));
        delete.selectedProperty().addListener((observable, wasSelected, selected) -> persistSettings(restart, delete, desktopShortcut, restoring));
        desktopShortcut.selectedProperty().addListener((observable, wasSelected, selected) -> persistSettings(restart, delete, desktopShortcut, restoring));
        VBox applicationOptions = settingsGroup("Приложения",
                settingOption(restart, "Только если оно работало до обновления. Самообновление AppFleet перезапускает менеджер по собственному протоколу."),
                settingOption(desktopShortcut, "Только при первой установке, если установщик поддерживает эту возможность. При обновлении прежний выбор сохраняется."));
        VBox downloadOptions = settingsGroup("Скачанные файлы",
                settingOption(delete, "После успешной установки или обновления. Файлы неудачной операции могут остаться для диагностики."));
        VBox body = new VBox(18, heading, description, applicationOptions, downloadOptions); body.setPadding(new Insets(28)); body.getStyleClass().add("settings-body");
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED); scroll.getStyleClass().add("settings-scroll");
        VBox page = new VBox(scroll); VBox.setVgrow(scroll, Priority.ALWAYS); page.getStyleClass().addAll("content-page", "settings-page");
        return page;
    }
    private static VBox settingsGroup(String title, Node... options) {
        Label heading = new Label(title); heading.getStyleClass().add("settings-group-heading");
        VBox group = new VBox(12); group.getChildren().add(heading); group.getChildren().addAll(options); group.getStyleClass().add("settings-card");
        return group;
    }
    private static VBox settingOption(CheckBox control, String helperText) {
        control.setWrapText(true);
        Label helper = new Label(helperText); helper.setWrapText(true); helper.getStyleClass().add("muted");
        VBox option = new VBox(3, control, helper); option.getStyleClass().add("settings-option");
        return option;
    }
    private void startInitialSequence() {
        service.record("AppFleet", "Запуск", "Успешно", "Запущена версия " + build.version(), null);
        if (developmentRun) {
            service.record("AppFleet", "Самообновление", "Пропущено", "Локальный запуск из исходников: самообновление отключено", null);
            refreshApplications();
            return;
        }
        selfUpdate.recoverAfterLaunch();
        selfUpdate.checkAsync(startupWorker).whenComplete((offer, failure) -> Platform.runLater(() -> {
            if (failure != null) { service.record("AppFleet", "Проверка обновления AppFleet", "Ошибка", "Не удалось проверить обновление AppFleet", unwrap(failure)); refreshApplications(); return; }
            if (offer.isEmpty()) { service.record("AppFleet", "Проверка обновления AppFleet", "Успешно", "AppFleet актуален", null); refreshApplications(); return; }
            service.record("AppFleet", "Проверка обновления AppFleet", "Доступно обновление", "Доступна версия " + offer.get().manifest().version(), null);
            confirmSelfUpdate(offer.get());
        }));
    }
    private void confirmSelfUpdate(SelfUpdateOffer offer) {
        if (!showSelfUpdateConfirmation(offer)) { service.record("AppFleet", "Самообновление", "Отменено", "Пользователь отказался от обновления", null); refreshApplications(); return; }
        AtomicBoolean cancelled = new AtomicBoolean(); Stage progress = progressStage("Скачивание обновления AppFleet", cancelled);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return selfUpdate.install(offer, cancelled::get, updateProgress(progress), operationProgress(progress)); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); } }, startupWorker).whenComplete((started, failure) -> Platform.runLater(() -> { closeProgress(progress); if (failure != null) { service.record("AppFleet", "Самообновление", "Ошибка", "Не удалось запустить обновление", unwrap(failure)); showError("Обновление AppFleet не запущено", unwrap(failure).getMessage()); refreshApplications(); } else { String message = "Внешний установщик запущен; AppFleet будет перезапущен.\n\nПроверка файла: " + started.verification().summary(); service.record("AppFleet", "Самообновление", started.verification().hasWarning() ? "Предупреждение" : "Запущено", message, null); if (started.verification().hasWarning()) showInfo("Самообновление запущено", message); Platform.exit(); } }));
    }
    private boolean showSelfUpdateConfirmation(SelfUpdateOffer offer) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Обновление AppFleet"); dialog.setHeaderText("Доступна версия " + offer.manifest().version()); dialog.setResizable(true); dialog.getDialogPane().setPrefWidth(660);
        Label overview = new Label("Файл: " + offer.installer().name() + " (" + humanSize(offer.installer().size()) + ")\nSHA-256: будет проверен\nЦифровая подпись: будет проверена; отсутствие подписи не блокирует обновление.\nИсточник: " + offer.release().htmlUrl()); overview.setWrapText(true);
        VBox content = new VBox(10, overview, new Separator(), releaseNotesArea(offer.release().body())); dialog.getDialogPane().setContent(content);
        ButtonType update = new ButtonType("Обновить", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(update, ButtonType.CANCEL); dialog.setResultConverter(button -> button);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == update;
    }
    private void refreshApplications() {
        if (checkUpdatesButton != null && checkUpdatesButton.isDisable()) return;
        if (checkUpdatesButton != null) checkUpdatesButton.setDisable(true);
        setStatusLine("Проверка обновлений…", false);
        service.checkAll().whenComplete((checked, failure) -> Platform.runLater(() -> {
            if (checkUpdatesButton != null) checkUpdatesButton.setDisable(false);
            if (failure != null) { setStatusLine("Ошибка проверки обновлений", true); showError("Не удалось проверить репозитории", unwrap(failure).getMessage()); return; }
            applications.setAll(checked);
            long stale = checked.stream().filter(snapshot -> !freshnessWarning(snapshot).isBlank()).count();
            String timestamp = DATE_TIME.format(java.time.Instant.now());
            setStatusLine(stale == 0
                    ? "Последняя проверка: " + timestamp + " · репозиториев: " + checked.size()
                    : "Последняя попытка проверки: " + timestamp + " · не подтверждено: " + stale,
                    stale != 0);
        }));
    }
    private void addRepository() {
        TextInputDialog dialog = new TextInputDialog(); dialog.initOwner(stage); dialog.setTitle("Добавить репозиторий"); dialog.setHeaderText("Публичный GitHub-репозиторий"); dialog.setContentText("Ссылка:");
        dialog.showAndWait().filter(value -> !value.isBlank()).ifPresent(url -> service.addRepository(url).whenComplete((added, failure) -> Platform.runLater(() -> { if (failure != null) showError("Репозиторий не добавлен", unwrap(failure).getMessage()); else { applications.setAll(service.currentSnapshots()); statusLine.setText("Репозиторий добавлен: " + added.repository().slug()); if (!added.choiceCandidates().isEmpty()) chooseAsset(added); } })));
    }
    private void chooseAsset(ApplicationSnapshot snapshot) {
        ListView<ReleaseAsset> list = new ListView<>(FXCollections.observableArrayList(assetCandidates(snapshot))); list.setCellFactory(ignored -> new ListCell<>() { @Override protected void updateItem(ReleaseAsset item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.name() + " · " + item.packageType() + " · " + item.architecture() + " · " + humanSize(item.size())); } });
        if (snapshot.selectedAsset() != null) list.getSelectionModel().select(snapshot.selectedAsset());
        Dialog<ReleaseAsset> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Выберите файл релиза"); dialog.setHeaderText(snapshot.repository().slug()); dialog.setResizable(true); dialog.getDialogPane().setPrefWidth(620); dialog.getDialogPane().setContent(list); ButtonType choose = new ButtonType("Выбрать", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(choose, ButtonType.CANCEL);
        Node chooseButton = dialog.getDialogPane().lookupButton(choose); chooseButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(button -> button == choose ? list.getSelectionModel().getSelectedItem() : null); dialog.showAndWait().ifPresent(asset -> service.chooseAsset(snapshot.repository(), asset).whenComplete((updated, failure) -> Platform.runLater(() -> { if (failure != null) showError("Не удалось сохранить выбор", unwrap(failure).getMessage()); else applications.setAll(service.currentSnapshots()); })));
    }
    private void removeRepository(ApplicationSnapshot snapshot) { ButtonType remove = new ButtonType("Удалить из списка", ButtonBar.ButtonData.OK_DONE); Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Приложение будет удалено только из списка AppFleet. Установленная программа останется в Windows.", remove, ButtonType.CANCEL); confirm.initOwner(stage); confirm.setHeaderText("Удалить " + snapshot.repository().slug() + " из списка?"); if (confirm.showAndWait().orElse(ButtonType.CANCEL) == remove) service.removeRepository(snapshot.repository()).whenComplete((nothing, failure) -> Platform.runLater(() -> { if (failure != null) showError("Не удалось удалить запись", unwrap(failure).getMessage()); else applications.setAll(service.currentSnapshots()); })); }
    private void actOn(ApplicationSnapshot snapshot) {
        if (snapshot.status() == AppStatus.CHECK_ERROR) {
            refreshApplications();
            return;
        }
        if (isAppFleetRepository(snapshot)) {
            if (developmentRun) {
                showInfo("Самообновление отключено", "Локальный запуск из исходников не может заменять установленный AppFleet.");
                return;
            }
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
            closeProgress(progress);
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
            closeProgress(progress);
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
        String signatureNotice = preview.packageType() != ru.pashaapps.appfleet.domain.PackageType.ZIP
                ? "\nЦифровая подпись: будет проверена; отсутствие подписи не блокирует установку."
                : "\nЦифровая подпись: не применяется к ZIP-пакету.";
        Label details = new Label("Текущая версия: " + emptyToDash(preview.currentVersion()) + "\nУстанавливаемая версия: " + preview.targetVersion() + "\nФайл: " + preview.assetName() + " (" + humanSize(preview.assetSize()) + ")\nИсточник: " + preview.sourceUrl() + "\nSHA-256: " + (preview.sha256Available() ? "будет проверен" : "не опубликован") + signatureNotice + (preview.thirdPartyInteractiveExeWarning() ? "\n\nВнимание: сторонний EXE будет запущен интерактивно." : "")); details.setWrapText(true);
        CheckBox close = new CheckBox("Закрыть запущенное приложение автоматически, если оно обнаружено"); close.setWrapText(true);
        VBox content = new VBox(12, details, new Separator(), releaseNotesArea(preview.releaseDescription()), new Separator(), close); content.setPrefWidth(620); dialog.setResizable(true); dialog.getDialogPane().setPrefWidth(660); dialog.getDialogPane().setContent(content); ButtonType execute = new ButtonType(preview.currentVersion() == null ? "Установить" : "Обновить", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(execute, ButtonType.CANCEL); dialog.setResultConverter(button -> button == execute ? new OperationRequest(true, close.isSelected(), false) : OperationRequest.cancelled()); return dialog.showAndWait().filter(OperationRequest::confirmed);
    }
    private TextArea releaseNotesArea(String notes) {
        TextArea area = new TextArea(notes == null || notes.isBlank() ? "Описание релиза не опубликовано." : notes); area.setEditable(false); area.setWrapText(true); area.setPrefRowCount(7); area.setPrefHeight(160); area.setMaxHeight(220); VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }
    private Stage progressStage(String title, AtomicBoolean cancelled) {
        Stage popup = new Stage(); popup.initOwner(stage); popup.initModality(Modality.WINDOW_MODAL); popup.setTitle(title);
        ProgressBar bar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS); bar.setPrefWidth(330);
        Label phase = new Label(OperationPhase.PREPARING.display()); phase.getStyleClass().add("operation-phase");
        Label detail = new Label(); detail.setWrapText(true); detail.getStyleClass().add("muted");
        Button cancel = new Button("Отмена");
        VBox box = new VBox(10, phase, detail, bar, cancel); box.setPadding(new Insets(20)); box.setAlignment(Pos.CENTER_LEFT); box.getStyleClass().add("operation-progress");
        javafx.scene.Scene scene = new javafx.scene.Scene(box); if (stage.getScene() != null) scene.getStylesheets().addAll(stage.getScene().getStylesheets()); popup.setScene(scene); popup.getIcons().setAll(stage.getIcons()); popup.setResizable(false);
        popup.getProperties().put("bar", bar); popup.getProperties().put("phaseLabel", phase); popup.getProperties().put("detailLabel", detail); popup.getProperties().put("cancel", cancel); popup.getProperties().put("phase", OperationPhase.PREPARING); popup.getProperties().put("allowClose", false);
        cancel.setOnAction(event -> requestCancellation(popup, cancelled));
        popup.setOnCloseRequest(event -> { if (Boolean.TRUE.equals(popup.getProperties().get("allowClose"))) return; event.consume(); requestCancellation(popup, cancelled); });
        popup.show();
        return popup;
    }
    private DownloadProgress updateProgress(Stage popup) {
        return new CoalescingDownloadProgress((received, total) -> Platform.runLater(() -> {
            if (popup.getProperties().get("phase") != OperationPhase.DOWNLOADING) return;
            ProgressBar bar = (ProgressBar) popup.getProperties().get("bar");
            Label label = (Label) popup.getProperties().get("detailLabel");
            bar.setProgress(total <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : (double) received / total);
            label.setText(total <= 0 ? humanSize(received) + " скачано" : humanSize(received) + " из " + humanSize(total));
        }));
    }
    private OperationProgress operationProgress(Stage popup) { return phase -> Platform.runLater(() -> showOperationPhase(popup, phase)); }
    private void showOperationPhase(Stage popup, OperationPhase phase) {
        popup.getProperties().put("phase", phase);
        Label label = (Label) popup.getProperties().get("phaseLabel"); Label detail = (Label) popup.getProperties().get("detailLabel"); ProgressBar bar = (ProgressBar) popup.getProperties().get("bar"); Button cancel = (Button) popup.getProperties().get("cancel");
        label.setText(phase.display());
        if (phase != OperationPhase.DOWNLOADING) { detail.setText(""); bar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS); }
        cancel.setDisable(!phase.cancellable() || cancel.isDisable());
    }
    private void requestCancellation(Stage popup, AtomicBoolean cancelled) {
        OperationPhase phase = (OperationPhase) popup.getProperties().get("phase");
        if (phase == null || !phase.cancellable() || !cancelled.compareAndSet(false, true)) return;
        ((Button) popup.getProperties().get("cancel")).setDisable(true);
        ((Label) popup.getProperties().get("detailLabel")).setText("Отмена запрошена…");
    }
    private static void closeProgress(Stage popup) { popup.getProperties().put("allowClose", true); popup.close(); }
    private void refreshJournal() { journalTable.setItems(FXCollections.observableArrayList(service.journalEntries())); }
    private void showTechnicalDetails(OperationEntry entry) { Alert detail = new Alert(Alert.AlertType.INFORMATION); detail.initOwner(stage); detail.setTitle("Технические подробности"); detail.setHeaderText(entry.operation() + " — " + entry.result()); TextArea area = new TextArea(entry.technicalDetails().isBlank() ? entry.message() : entry.technicalDetails()); area.setEditable(false); area.setWrapText(false); area.setPrefColumnCount(90); area.setPrefRowCount(20); detail.getDialogPane().setContent(area); detail.showAndWait(); }
    private static List<ReleaseAsset> assetCandidates(ApplicationSnapshot snapshot) {
        if (!snapshot.choiceCandidates().isEmpty()) return snapshot.choiceCandidates();
        if (snapshot.release() == null) return List.of();
        return new ru.pashaapps.appfleet.domain.AssetSelector().eligibleAssets(snapshot.release());
    }
    private void persistSettings(CheckBox restart, CheckBox delete, CheckBox desktopShortcut, AtomicBoolean restoring) {
        if (restoring.get()) return;
        UserSettings previous = service.settings();
        try {
            service.saveSettings(new UserSettings(restart.isSelected(), delete.isSelected(), desktopShortcut.isSelected()));
        } catch (Exception failure) {
            restoring.set(true);
            try {
                restart.setSelected(previous.restartPreviouslyRunningApp());
                delete.setSelected(previous.deleteInstallerAfterSuccess());
                desktopShortcut.setSelected(previous.createDesktopShortcutForNewApplications());
            } finally {
                restoring.set(false);
            }
            showError("Не удалось сохранить настройки", failure.getMessage());
        }
    }
    private void setStatusLine(String text, boolean warning) {
        statusLine.setText(text);
        statusLine.getStyleClass().removeAll("status-warning", "status-error");
        if (warning) statusLine.getStyleClass().add("status-warning");
    }
    private void showError(String title, String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Неизвестная ошибка" : message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText(title); alert.showAndWait(); }
    private void showInfo(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText(title); alert.showAndWait(); }
    private static String emptyToDash(String value) { return value == null || value.isBlank() ? "Не установлено" : value; }
    private static String humanSize(long bytes) { if (bytes < 1024) return bytes + " Б"; if (bytes < 1024L * 1024) return String.format("%.1f КБ", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / 1024.0 / 1024.0); return String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0); }
    private static Throwable unwrap(Throwable failure) { return failure.getCause() == null ? failure : failure.getCause(); }
}
