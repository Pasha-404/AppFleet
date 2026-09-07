package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.file.Path;

/** Windows Shell launch boundary used only after CreateProcess reports ERROR_ELEVATION_REQUIRED. */
@FunctionalInterface
interface ElevatedInstallerLauncher {
    int launchAndWait(Path installer) throws IOException;
}
