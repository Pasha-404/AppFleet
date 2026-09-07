package ru.pashaapps.appfleet.install;

import java.io.IOException;

/** The user declined the Windows elevation consent dialog; no installer was launched. */
public final class UacCancelledException extends IOException {
    public UacCancelledException() { super("Пользователь отменил запрос контроля учётных записей Windows (UAC)"); }
}
