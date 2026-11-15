package com.vynk.coparent_backend.account.service;

import com.vynk.coparent_backend.account.dto.ChangePasswordRequest;
import com.vynk.coparent_backend.auth.repository.RefreshTokenRepository;
import com.vynk.coparent_backend.auth.service.AuthService;
import com.vynk.coparent_backend.auth.service.AuthSession;
import com.vynk.coparent_backend.auth.service.PasswordPolicy;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.family.service.RelativeService;
import com.vynk.coparent_backend.auth.service.RateLimiter.Action;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.entity.UserStatus;
import com.vynk.coparent_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** « Mon compte » (spec §1): password change and account deletion, both confirmed by the current password. */
@Service
@RequiredArgsConstructor
public class AccountService {

    /** Personal data is deleted within 30 days of the request (spec §1). */
    static final Duration DELETION_DELAY = Duration.ofDays(30);
    private static final String WRONG_PASSWORD = "Mot de passe actuel incorrect.";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimiter rateLimiter;
    private final RelativeService relativeService;
    private final Clock clock;

    /** New password; every session is closed and a new one is returned, so this phone stays signed in. */
    @Transactional
    public AuthSession changePassword(UUID userId, ChangePasswordRequest request) {
        User user = activeUser(userId);
        checkPassword(user, request.currentPassword());
        passwordPolicy.validate(request.newPassword(), request.confirmPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllForUser(user.getId(), clock.instant());
        return authService.newSession(user);
    }

    /**
     * Deactivates the account at once (no sign-in, every session closed); {@link AccountPurgeJob} deletes its
     * personal data once {@code deletionDueAt} is reached. The family, calendar and messages stay with the other
     * parent.
     */
    @Transactional
    public void deleteAccount(UUID userId, String password) {
        User user = activeUser(userId);
        checkPassword(user, password);
        Instant now = clock.instant();
        user.setStatus(UserStatus.DEACTIVATED);
        user.setDeactivatedAt(now);
        user.setDeletionDueAt(now.plus(DELETION_DELAY));
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
        relativeService.leaveAll(user.getId());
    }

    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Session invalide."));
    }

    private void checkPassword(User user, String password) {
        rateLimiter.consume(Action.ACCOUNT_PASSWORD_CHECK, user.getId().toString());
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.forbidden(WRONG_PASSWORD);
        }
    }
}
