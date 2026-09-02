package ru.pashaapps.appfleet.persistence;

public record UserSettings(Boolean restartPreviouslyRunningApp, Boolean deleteInstallerAfterSuccess, Boolean createDesktopShortcutForNewApplications) {
    public static UserSettings defaults() { return new UserSettings(true, true, false); }
    public UserSettings normalized() {
        return new UserSettings(restartPreviouslyRunningApp == null || restartPreviouslyRunningApp, deleteInstallerAfterSuccess == null || deleteInstallerAfterSuccess,
                createDesktopShortcutForNewApplications != null && createDesktopShortcutForNewApplications);
    }
}
