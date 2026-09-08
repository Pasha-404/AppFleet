package ru.pashaapps.appfleet.service;

/** User-visible phase of an installation attempt. Cancellation is only accepted before launch. */
public enum OperationPhase {
    PREPARING("Подготовка…", true),
    DOWNLOADING("Скачивание…", true),
    VERIFYING("Проверка файла…", false),
    LAUNCHING_INSTALLER("Запуск установщика…", false),
    WAITING_FOR_INSTALLER("Ожидание завершения установщика…", false),
    CONFIRMING_INSTALLATION("Подтверждение установки…", false),
    FINISHED("Завершение…", false);

    private final String display;
    private final boolean cancellable;

    OperationPhase(String display, boolean cancellable) {
        this.display = display;
        this.cancellable = cancellable;
    }

    public String display() { return display; }
    public boolean cancellable() { return cancellable; }
}
