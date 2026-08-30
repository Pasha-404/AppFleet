package ru.pashaapps.appfleet.service;

public enum AppStatus {
    CHECKING("Проверка"), NOT_INSTALLED("Не установлено"), UP_TO_DATE("Установлена актуальная версия"), UPDATE_AVAILABLE("Доступно обновление"),
    VERSION_UNKNOWN("Версия не определена"), NO_RELEASES("Релизы не найдены"), ASSET_SELECTION_REQUIRED("Требуется выбрать файл"),
    CHECK_ERROR("Ошибка проверки"), DOWNLOADING("Скачивание"), INSTALLING("Установка"), UPDATED("Обновлено"), USER_ACTION_REQUIRED("Требуется действие пользователя");
    private final String display;
    AppStatus(String display) { this.display = display; }
    public String display() { return display; }
}

