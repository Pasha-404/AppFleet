package ru.pashaapps.appfleet.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import ru.pashaapps.appfleet.BuildInfo;
import ru.pashaapps.appfleet.domain.ReleaseAsset;
import ru.pashaapps.appfleet.domain.RepositoryId;
import ru.pashaapps.appfleet.install.CancellationToken;
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

/** The fixed sidebar, fixed command panel and table-oriented primary UI. */
public final class MainWindow {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final Stage stage;
    private final AppFleetService service;
    private final SelfUpdateService selfUpdate;
    private final BuildInfo build;
    private final ExecutorService startupWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("appfleet-startup-", 0).factory());
    private final ObservableList<ApplicationSnapshot> applications = FXCollections.observableArrayList();
    private final TableView<ApplicationSnapshot> applicationsTable = new TableView<>(applications);
    private final TableView<OperationEntry> journalTable = new TableView<>();
    private final Label statusLine = new Label("Проверка ещё не выполнялась");
    private final StackPane content = new StackPane();
    private final Node applicationsPage = applicationsPage();
    private final Node journalPage = journalPage();

    public MainWindow(Stage stage, AppFleetService service, SelfUpdateService selfUpdate, BuildInfo build) {
        this.stage = stage;
        this.service = service;
        this.selfUpdate = selfUpdate;
        this.build = build;
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
        stage.setMinWidth(960); stage.setMinHeight(600); stage.setScene(scene); stage.show();
        startInitialSequence();
    }
    public void close() { startupWorker.shutdownNow(); service.close(); }

    private Node titleBar() {
        Label title = new Label("AppFleet"); title.getStyleClass().add("app-title");
        HBox bar = new HBox(title); bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(12, 20, 12, 20)); bar.getStyleClass().add("title-bar");
        return bar;
    }
    private Node sidebar() {
        Button apps = navigationButton("Приложения", () -> content.getChildren().setAll(applicationsPage));
        Button journal = navigationButton("Журнал", () -> { refreshJournal(); content.getChildren().setAll(journalPage); });
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        CheckBox restart = new CheckBox("Повторно запускать приложение после обновления, если оно было запущено"); restart.setWrapText(true); restart.setSelected(service.settings().restartPreviouslyRunningApp());
        CheckBox delete = new CheckBox("Удалять скачанные установщики после успешной установки"); delete.setWrapText(true); delete.setSelected(service.settings().deleteInstallerAfterSuccess());
        restart.selectedProperty().addListener((observable, wasSelected, selected) -> saveSettings(new UserSettings(selected, delete.isSelected())));
        delete.selectedProperty().addListener((observable, wasSelected, selected) -> saveSettings(new UserSettings(restart.isSelected(), selected)));
        Label version = new Label("Версия AppFleet: " + build.version()); version.setWrapText(true); version.getStyleClass().add("muted");
        VBox box = new VBox(8, apps, journal, spacer, new Separator(), restart, delete, new Separator(), version);
        box.setPrefWidth(255); box.setMinWidth(220); box.setPadding(new Insets(18, 14, 18, 14)); box.getStyleClass().add("sidebar");
        return box;
    }
    private Button navigationButton(String text, Runnable action) { Button button = new Button(text); button.setMaxWidth(Double.MAX_VALUE); button.getStyleClass().add("navigation"); button.setOnAction(event -> action.run()); return button; }
    private Node applicationsPage() {
        Label heading = new Label("Приложения"); heading.getStyleClass().add("page-heading");
        Button check = new Button("Проверить"); check.setOnAction(event -> refreshApplications());
        Button add = new Button("Добавить репозиторий"); add.getStyleClass().add("primary"); add.setOnAction(event -> addRepository());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox commands = new HBox(12, heading, statusLine, spacer, check, add); commands.setAlignment(Pos.CENTER_LEFT); commands.setPadding(new Insets(20, 24, 12, 24)); commands.getStyleClass().add("command-bar");
        configureApplicationsTable();
        VBox page = new VBox(commands, applicationsTable); VBox.setVgrow(applicationsTable, Priority.ALWAYS); page.getStyleClass().add("content-page");
        return page;
    }
    private void configureApplicationsTable() {
        applicationsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        applicationsTable.getColumns().setAll(
                textColumn("Приложение", 235, item -> item.displayName() + "\n" + item.repository().slug()),
                textColumn("Состояние", 185, item -> item.status().display()),
                textColumn("Установленная версия", 145, item -> emptyToDash(item.installedVersion())),
                textColumn("Последняя версия", 135, ApplicationSnapshot::latestVersion),
                textColumn("Дата релиза", 145, item -> item.release() == null ? "—" : DATE_TIME.format(item.release().publishedAt())),
                textColumn("Файл", 245, ApplicationSnapshot::assetName),
                textColumn("Размер", 105, item -> item.selectedAsset() == null ? "—" : humanSize(item.selectedAsset().size())),
                actionColumn(), menuColumn());
        applicationsTable.setPlaceholder(new Label("Добавьте публичный GitHub-репозиторий, чтобы начать."));
        applicationsTable.setMinWidth(1180);
    }
    private TableColumn<ApplicationSnapshot, String> textColumn(String title, double width, Function<ApplicationSnapshot, String> value) {
        TableColumn<ApplicationSnapshot, String> column = new TableColumn<>(title); column.setPrefWidth(width); column.setMinWidth(width);
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data.getValue())));
        column.setCellFactory(ignored -> { TableCell<ApplicationSnapshot, String> cell = new TableCell<>(); cell.setWrapText(true); return cell; });
        return column;
    }
    private TableColumn<ApplicationSnapshot, Void> actionColumn() {
        TableColumn<ApplicationSnapshot, Void> column = new TableColumn<>("Действие"); column.setPrefWidth(120); column.setMinWidth(120);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Button action = new Button();
            { action.setOnAction(event -> actOn(getTableRow().getItem())); }
            @Override protected void updateItem(Void value, boolean empty) { super.updateItem(value, empty); ApplicationSnapshot row = getTableRow() == null ? null : getTableRow().getItem(); if (empty || row == null) { setGraphic(null); return; } String label = switch (row.status()) { case NOT_INSTALLED -> "Установить"; case UPDATE_AVAILABLE -> "Обновить"; case CHECK_ERROR -> "Повторить"; case UP_TO_DATE -> "Актуально"; default -> "Недоступно"; }; action.setText(label); action.setDisable(!(row.status() == AppStatus.NOT_INSTALLED || row.status() == AppStatus.UPDATE_AVAILABLE || row.status() == AppStatus.CHECK_ERROR)); setGraphic(action); }
        });
        return column;
    }
    private TableColumn<ApplicationSnapshot, Void> menuColumn() {
        TableColumn<ApplicationSnapshot, Void> column = new TableColumn<>("…"); column.setPrefWidth(72); column.setMinWidth(72);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(Void value, boolean empty) { super.updateItem(value, empty); ApplicationSnapshot row = getTableRow() == null ? null : getTableRow().getItem(); if (empty || row == null) { setGraphic(null); return; }
                MenuItem choose = new MenuItem("Выбрать файл релиза"); choose.setDisable(row.release() == null); choose.setOnAction(event -> chooseAsset(row));
                MenuItem remove = new MenuItem("Удалить из списка"); remove.setOnAction(event -> removeRepository(row)); setGraphic(new MenuButton("…", null, choose, new SeparatorMenuItem(), remove)); }
        });
        return column;
    }
    private Node journalPage() {
        Label heading = new Label("Журнал"); heading.getStyleClass().add("page-heading");
        Button refresh = new Button("Обновить"); refresh.setOnAction(event -> refreshJournal());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox commands = new HBox(12, heading, spacer, refresh); commands.setAlignment(Pos.CENTER_LEFT); commands.setPadding(new Insets(20, 24, 12, 24)); commands.getStyleClass().add("command-bar");
        journalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        journalTable.getColumns().setAll(journalColumn("Дата и время", 155, entry -> DATE_TIME.format(entry.occurredAt())), journalColumn("Приложение", 170, OperationEntry::repository), journalColumn("Операция", 180, OperationEntry::operation), journalColumn("Результат", 125, OperationEntry::result), journalColumn("Описание", 390, OperationEntry::message));
        journalTable.setRowFactory(ignored -> { TableRow<OperationEntry> row = new TableRow<>(); row.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && !row.isEmpty()) showTechnicalDetails(row.getItem()); }); return row; });
        VBox page = new VBox(commands, journalTable); VBox.setVgrow(journalTable, Priority.ALWAYS); page.getStyleClass().add("content-page"); return page;
    }
    private TableColumn<OperationEntry, String> journalColumn(String title, double width, Function<OperationEntry, String> mapper) { TableColumn<OperationEntry, String> column = new TableColumn<>(title); column.setPrefWidth(width); column.setCellValueFactory(data -> new ReadOnlyStringWrapper(mapper.apply(data.getValue()))); return column; }
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
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return selfUpdate.install(offer, cancelled::get, updateProgress(progress)); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); } }, startupWorker).whenComplete((started, failure) -> Platform.runLater(() -> { progress.close(); if (failure != null) { service.record("AppFleet", "Самообновление", "Ошибка", "Не удалось запустить обновление", unwrap(failure)); showError("Обновление AppFleet не запущено", unwrap(failure).getMessage()); refreshApplications(); } else { service.record("AppFleet", "Самообновление", "Запущено", "Внешний установщик запущен; AppFleet будет перезапущен", null); Platform.exit(); } }));
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
    private void actOn(ApplicationSnapshot snapshot) { if (snapshot.status() == AppStatus.CHECK_ERROR) { refreshApplications(); return; } OperationPreview preview; try { preview = service.preview(snapshot.repository()); } catch (Exception failure) { showError("Операция недоступна", failure.getMessage()); return; } showOperationConfirmation(preview).ifPresent(request -> runOperation(snapshot, request)); }
    private void runOperation(ApplicationSnapshot snapshot, OperationRequest request) {
        AtomicBoolean cancelled = new AtomicBoolean(); Stage progress = progressStage("Скачивание и установка", cancelled);
        service.installOrUpdate(snapshot.repository(), request, cancelled::get, updateProgress(progress)).whenComplete((result, failure) -> Platform.runLater(() -> {
            progress.close();
            if (failure != null) { showError("Операция завершилась ошибкой", unwrap(failure).getMessage()); return; }
            applications.setAll(service.currentSnapshots());
            if (!result.successful() && result.message().contains("требуется отдельное подтверждение")) {
                Alert force = new Alert(Alert.AlertType.CONFIRMATION, "Штатное закрытие приложения не удалось. Разрешить принудительно завершить только этот ранее обнаруженный процесс и продолжить установку?", ButtonType.OK, ButtonType.CANCEL);
                force.initOwner(stage); force.setHeaderText("Требуется отдельное подтверждение");
                if (force.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) runOperation(snapshot, new OperationRequest(true, true, true));
                return;
            }
            Alert outcome = new Alert(result.successful() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR, result.message(), ButtonType.OK); outcome.initOwner(stage); outcome.setHeaderText(result.successful() ? "Операция завершена" : "Операция не выполнена"); outcome.showAndWait();
        }));
    }
    private Optional<OperationRequest> showOperationConfirmation(OperationPreview preview) {
        Dialog<OperationRequest> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Подтверждение установки"); dialog.setHeaderText(preview.application().displayName() + " — " + preview.targetVersion());
        Label details = new Label("Текущая версия: " + emptyToDash(preview.currentVersion()) + "\nУстанавливаемая версия: " + preview.targetVersion() + "\nФайл: " + preview.assetName() + " (" + humanSize(preview.assetSize()) + ")\nИсточник: " + preview.sourceUrl() + "\nSHA-256: " + (preview.sha256Available() ? "будет проверен" : "не опубликован") + "\n\n" + preview.releaseDescription() + (preview.thirdPartyInteractiveExeWarning() ? "\n\nВнимание: сторонний EXE будет запущен интерактивно." : "")); details.setWrapText(true);
        CheckBox close = new CheckBox("Закрыть запущенное приложение автоматически, если оно обнаружено"); close.setWrapText(true);
        VBox content = new VBox(12, details, new Separator(), close); content.setPrefWidth(600); dialog.getDialogPane().setContent(content); ButtonType execute = new ButtonType(preview.currentVersion() == null ? "Установить" : "Обновить", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(execute, ButtonType.CANCEL); dialog.setResultConverter(button -> button == execute ? new OperationRequest(true, close.isSelected(), false) : OperationRequest.cancelled()); return dialog.showAndWait().filter(OperationRequest::confirmed);
    }
    private Stage progressStage(String title, AtomicBoolean cancelled) { Stage popup = new Stage(); popup.initOwner(stage); popup.initModality(Modality.WINDOW_MODAL); popup.setTitle(title); ProgressBar bar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS); bar.setPrefWidth(330); Label label = new Label("Подготовка…"); Button cancel = new Button("Отмена"); cancel.setOnAction(event -> { cancelled.set(true); cancel.setDisable(true); label.setText("Отмена…"); }); VBox box = new VBox(12, label, bar, cancel); box.setPadding(new Insets(20)); box.setAlignment(Pos.CENTER); popup.setScene(new javafx.scene.Scene(box)); popup.setResizable(false); popup.show(); popup.getProperties().put("bar", bar); popup.getProperties().put("label", label); return popup; }
    private DownloadProgress updateProgress(Stage popup) { return (received, total) -> Platform.runLater(() -> { ProgressBar bar = (ProgressBar) popup.getProperties().get("bar"); Label label = (Label) popup.getProperties().get("label"); bar.setProgress(total <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : (double) received / total); label.setText(total <= 0 ? humanSize(received) + " скачано" : humanSize(received) + " из " + humanSize(total)); }); }
    private void refreshJournal() { journalTable.setItems(FXCollections.observableArrayList(service.journalEntries())); }
    private void showTechnicalDetails(OperationEntry entry) { Alert detail = new Alert(Alert.AlertType.INFORMATION); detail.initOwner(stage); detail.setTitle("Технические подробности"); detail.setHeaderText(entry.operation() + " — " + entry.result()); TextArea area = new TextArea(entry.technicalDetails().isBlank() ? entry.message() : entry.technicalDetails()); area.setEditable(false); area.setWrapText(false); area.setPrefColumnCount(90); area.setPrefRowCount(20); detail.getDialogPane().setContent(area); detail.showAndWait(); }
    private static List<ReleaseAsset> assetCandidates(ApplicationSnapshot snapshot) {
        if (!snapshot.choiceCandidates().isEmpty()) return snapshot.choiceCandidates();
        if (snapshot.release() == null) return List.of();
        return new ru.pashaapps.appfleet.domain.AssetSelector().eligibleAssets(snapshot.release());
    }
    private void saveSettings(UserSettings settings) { try { service.saveSettings(settings); } catch (Exception failure) { showError("Не удалось сохранить настройки", failure.getMessage()); } }
    private void showError(String title, String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Неизвестная ошибка" : message, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText(title); alert.showAndWait(); }
    private static String emptyToDash(String value) { return value == null || value.isBlank() ? "Не установлено" : value; }
    private static String humanSize(long bytes) { if (bytes < 1024) return bytes + " Б"; if (bytes < 1024L * 1024) return String.format("%.1f КБ", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / 1024.0 / 1024.0); return String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0); }
    private static Throwable unwrap(Throwable failure) { return failure.getCause() == null ? failure : failure.getCause(); }
}
