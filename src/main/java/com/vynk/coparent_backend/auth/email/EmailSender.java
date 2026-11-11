package com.vynk.coparent_backend.auth.email;

import com.vynk.coparent_backend.user.entity.User;

/** Sends account emails. Texts must stay neutral and factual (spec §9). */
public interface EmailSender {

    void sendEmailVerification(User user, String link);

    void sendPasswordReset(User user, String link);
}
