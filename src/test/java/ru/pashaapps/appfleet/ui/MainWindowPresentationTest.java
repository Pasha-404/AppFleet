package ru.pashaapps.appfleet.ui;

import org.junit.jupiter.api.Test;
import ru.pashaapps.appfleet.service.AppStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainWindowPresentationTest {
    @Test void exposesInstallUpdateAndRetryInTheCardItself() {
        assertEquals("Установить", MainWindow.cardAction(AppStatus.NOT_INSTALLED).label());
        assertEquals("Обновить", MainWindow.cardAction(AppStatus.UPDATE_AVAILABLE).label());
        assertEquals("Повторить", MainWindow.cardAction(AppStatus.CHECK_ERROR).label());
        assertTrue(MainWindow.cardAction(AppStatus.NOT_INSTALLED).enabled());
        assertTrue(MainWindow.cardAction(AppStatus.UPDATE_AVAILABLE).enabled());
        assertTrue(MainWindow.cardAction(AppStatus.CHECK_ERROR).enabled());
    }

    @Test void keepsNonActionStatesInformativeButDisabled() {
        assertEquals("Актуально", MainWindow.cardAction(AppStatus.UP_TO_DATE).label());
        assertFalse(MainWindow.cardAction(AppStatus.UP_TO_DATE).enabled());
        assertEquals("Недоступно", MainWindow.cardAction(AppStatus.ASSET_SELECTION_REQUIRED).label());
        assertFalse(MainWindow.cardAction(AppStatus.ASSET_SELECTION_REQUIRED).enabled());
    }
}
