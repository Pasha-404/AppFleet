package ru.pashaapps.appfleet.persistence;

public record UserSettings(Boolean restartPreviouslyRunningApp, Boolean deleteInstallerAfterSuccess) {
    public static UserSettings defaults() { return new UserSettings(true, true); }
    public UserSettings normalized() {
        return new UserSettings(restartPreviouslyRunningApp == null || restartPreviouslyRunningApp, deleteInstallerAfterSuccess == null || deleteInstallerAfterSuccess);
    }
}

