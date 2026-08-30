package ru.pashaapps.appfleet.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AtomicJsonStoreTest {
    @TempDir Path temporaryDirectory;
    @Test void readsTheLastKnownGoodBackupWhenPrimaryBecomesCorrupt() throws IOException {
        AtomicJsonStore<UserSettings> store = new AtomicJsonStore<>(AppFleetObjectMapper.create(), UserSettings.class, temporaryDirectory.resolve("settings.json"));
        store.write(new UserSettings(false, true));
        store.write(new UserSettings(true, false));
        Files.writeString(temporaryDirectory.resolve("settings.json"), "broken json");
        assertEquals(new UserSettings(false, true), store.read().orElseThrow());
    }
    @Test void missingSettingsDefaultToTrue() { assertEquals(UserSettings.defaults(), new UserSettings(null, null).normalized()); }
}

