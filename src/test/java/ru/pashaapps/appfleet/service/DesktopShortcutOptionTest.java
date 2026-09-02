package ru.pashaapps.appfleet.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopShortcutOptionTest {
    @Test void addsTheDeclaredTaskOnlyForANewInstallationWhenTheUserEnabledIt() {
        assertEquals(List.of("/VERYSILENT", "/CLOSEAPPLICATIONS", "/TASKS=desktopicon"),
                AppFleetService.withDesktopShortcutTask(List.of("/VERYSILENT", "/CLOSEAPPLICATIONS"), true, true, "desktopicon"));
    }

    @Test void leavesUpdateAndUndeclaredShortcutUntouched() {
        List<String> arguments = List.of("/VERYSILENT", "/CLOSEAPPLICATIONS");
        assertEquals(arguments, AppFleetService.withDesktopShortcutTask(arguments, false, true, "desktopicon"));
        assertEquals(arguments, AppFleetService.withDesktopShortcutTask(arguments, true, true, null));
        assertEquals(arguments, AppFleetService.withDesktopShortcutTask(arguments, true, false, "desktopicon"));
    }
}
