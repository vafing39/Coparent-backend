package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.user.entity.User;

/** Sends account emails. Texts must stay neutral and factual (spec §9). */
public interface EmailSender {

    /** {@code code}: the 8-digit verification code, typed by the user in the app. */
    void sendEmailVerification(User user, String code);

    /** {@code code}: the 8-digit password-reset code, typed by the user in the app. */
    void sendPasswordReset(User user, String code);

    /** {@code json}: the user's personal data (spec §1), sent as an attachment named {@code fileName}. */
    void sendDataExport(User user, String fileName, byte[] json);
}
