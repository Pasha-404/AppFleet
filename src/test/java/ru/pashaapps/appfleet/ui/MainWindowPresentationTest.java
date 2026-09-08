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
        assertEquals("Выбрать файл…", MainWindow.cardAction(AppStatus.ASSET_SELECTION_REQUIRED).label());
        assertEquals("Повторить проверку", MainWindow.cardAction(AppStatus.CHECK_ERROR).label());
        assertTrue(MainWindow.cardAction(AppStatus.NOT_INSTALLED).enabled());
        assertTrue(MainWindow.cardAction(AppStatus.UPDATE_AVAILABLE).enabled());
        assertTrue(MainWindow.cardAction(AppStatus.ASSET_SELECTION_REQUIRED).enabled());
        assertTrue(MainWindow.cardAction(AppStatus.CHECK_ERROR).enabled());
        assertEquals(MainWindow.CardActionKind.INSTALL_OR_UPDATE, MainWindow.cardAction(AppStatus.NOT_INSTALLED).kind());
        assertEquals(MainWindow.CardActionKind.CHOOSE_ASSET, MainWindow.cardAction(AppStatus.ASSET_SELECTION_REQUIRED).kind());
        assertEquals(MainWindow.CardActionKind.RETRY_CHECK, MainWindow.cardAction(AppStatus.CHECK_ERROR).kind());
    }

    @Test void keepsNonActionStatesFreeOfDisabledPrimaryButtons() {
        assertEquals("", MainWindow.cardAction(AppStatus.UP_TO_DATE).label());
        assertFalse(MainWindow.cardAction(AppStatus.UP_TO_DATE).enabled());
        assertEquals(MainWindow.CardActionKind.NONE, MainWindow.cardAction(AppStatus.UP_TO_DATE).kind());
        assertFalse(MainWindow.cardAction(AppStatus.USER_ACTION_REQUIRED).enabled());
    }

    @Test void separatesFreshnessFromTheVersionStatus() {
        assertEquals("Не удалось проверить обновления. Повторите проверку.", MainWindow.freshnessWarning(AppStatus.CHECK_ERROR, "HTTP 429"));
        assertEquals("Не удалось проверить обновления. Показаны сохранённые данные.", MainWindow.freshnessWarning(AppStatus.UP_TO_DATE, "Показаны последние подтверждённые данные."));
        assertEquals("", MainWindow.freshnessWarning(AppStatus.UP_TO_DATE, "Файл выбран по manifest"));
    }
}
