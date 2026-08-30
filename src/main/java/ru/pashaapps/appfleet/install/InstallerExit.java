package ru.pashaapps.appfleet.install;

public record InstallerExit(int code, boolean successful, boolean restartRequired, String message) {
    public static InstallerExit forGeneric(int code) { return new InstallerExit(code, code == 0, false, code == 0 ? "Установщик завершён" : "Установщик завершился с кодом " + code); }
    public static InstallerExit forMsi(int code) {
        return switch (code) {
            case 0 -> new InstallerExit(0, true, false, "Установка MSI завершена");
            case 3010, 1641 -> new InstallerExit(code, true, true, "Windows Installer запросил перезагрузку");
            default -> new InstallerExit(code, false, false, "MSI завершился с кодом " + code);
        };
    }
}

