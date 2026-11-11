package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.user.entity.User;

/** Sends account emails. Texts must stay neutral and factual (spec §9). */
public interface EmailSender {

    /** {@code code}: the 8-digit verification code, typed by the user in the app. */
    void sendEmailVerification(User user, String code);

    /** {@code code}: the 8-digit password-reset code, typed by the user in the app. */
    void sendPasswordReset(User user, String code);
}
