package ru.pashaapps.appfleet.install;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.file.Path;

/** Bounded native handle ownership for a user-approved UAC launch of an unknown EXE installer. */
final class WindowsElevatedInstallerLauncher implements ElevatedInstallerLauncher {
    private static final int ERROR_CANCELLED = 1223;
    private static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;
    private static final int SEE_MASK_NOASYNC = 0x00000100;

    @Override
    public int launchAndWait(Path installer) throws IOException {
        ShellAPI.SHELLEXECUTEINFO execute = new ShellAPI.SHELLEXECUTEINFO();
        execute.cbSize = execute.size();
        execute.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
        execute.lpVerb = "runas";
        execute.lpFile = installer.toAbsolutePath().normalize().toString();
        execute.nShow = WinUser.SW_SHOWNORMAL;
        if (!Shell32.INSTANCE.ShellExecuteEx(execute)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_CANCELLED) throw new UacCancelledException();
            throw new IOException("Windows Shell не смог запустить installer с повышением прав (код " + error + ")");
        }
        WinNT.HANDLE process = execute.hProcess;
        if (process == null || Pointer.nativeValue(process.getPointer()) == 0) {
            throw new IOException("Windows Shell запустил installer без доступного process handle; AppFleet не может подтвердить результат установки");
        }
        try {
            int wait = Kernel32.INSTANCE.WaitForSingleObject(process, WinBase.INFINITE);
            if (wait != WinBase.WAIT_OBJECT_0) {
                throw new IOException("Не удалось дождаться elevated installer (код ожидания " + wait + ")");
            }
            IntByReference exitCode = new IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(process, exitCode)) {
                throw new IOException("Не удалось получить код завершения elevated installer (код " + Kernel32.INSTANCE.GetLastError() + ")");
            }
            return exitCode.getValue();
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }
}
