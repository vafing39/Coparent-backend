package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.auth.service.RateLimiter.Action;
import com.vynk.coparent_backend.auth.dto.LoginRequest;
import com.vynk.coparent_backend.auth.dto.ResetPasswordRequest;
import com.vynk.coparent_backend.auth.dto.SignupRequest;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.repository.UserRepository;
import com.vynk.coparent_backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import com.vynk.coparent_backend.auth.repository.RefreshTokenRepository;
import com.vynk.coparent_backend.auth.repository.OneTimeTokenRepository;
import com.vynk.coparent_backend.auth.entity.RefreshToken;
import com.vynk.coparent_backend.auth.entity.OneTimeToken;
import com.vynk.coparent_backend.auth.entity.OneTimeTokenType;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Email ou mot de passe incorrect.";
    private static final String INVALID_SESSION = "Session expirée. Veuillez vous reconnecter.";
    private static final String INVALID_CODE = "Code incorrect ou expiré.";

    private final UserRepository userRepository;
    private final UserService userService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimiter rateLimiter;
    private final EmailSender emailSender;
    private final AuthProperties properties;
    private final Clock clock;

    /** Hash compared against when the email is unknown, so response time doesn't reveal which accounts exist. */
    private String dummyPasswordHash;

    @Transactional
    public AuthSession signup(SignupRequest request, String clientIp) {
        rateLimiter.consume(Action.SIGNUP, clientIp);
        String email = normalizeEmail(request.email());
        passwordPolicy.validate(request.password(), request.confirmPassword());

        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Un compte existe déjà avec cet email.");
        }

        User user = new User(email, passwordEncoder.encode(request.password()), request.firstName().trim());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) { // concurrent signup with the same email
            throw ApiException.conflict("Un compte existe déjà avec cet email.");
        }

        sendEmailVerification(user);
        return startSession(user, UUID.randomUUID());
    }

    @Transactional
    public AuthSession login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        rateLimiter.consume(Action.LOGIN, clientIp + "|" + email);
        Optional<User> user = userRepository.findByEmail(email);
        String hash = user.map(User::getPasswordHash).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash != null ? hash : dummyHash());

        if (user.isEmpty() || hash == null || !passwordMatches || !user.get().isActive()) {
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }

        return startSession(user.get(), UUID.randomUUID());
    }

    /** Rotates the refresh token. Reusing a token that was already rotated revokes the whole session. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthSession refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokenRepository.findByTokenHash(tokenService.hash(rawRefreshToken))
                .orElseThrow(() -> ApiException.unauthorized(INVALID_SESSION));

        if (current.wasRotated()) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            throw ApiException.unauthorized(INVALID_SESSION);
        }

        if (!current.isUsable(now) || !current.getUser().isActive()) {
            throw ApiException.unauthorized(INVALID_SESSION);
        }

        IssuedSession issued = issueSession(current.getUser(), current.getFamilyId());
        current.rotateTo(issued.refreshToken(), now);

        return issued.session();
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(tokenService.hash(rawRefreshToken))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    /** Checks the 8-digit code sent by email. Attempts are limited per user, since a code can be guessed. */
    @Transactional
    public User verifyEmail(UUID userId, String code) {
        rateLimiter.consume(Action.EMAIL_VERIFY, userId.toString());
        User user = userService.getActiveUser(userId);
        Instant now = clock.instant();
        OneTimeToken token = oneTimeTokenRepository
                .findByTokenHashAndType(tokenService.hashVerificationCode(userId, code), OneTimeTokenType.EMAIL_VERIFICATION)
                .filter(t -> t.getUser().getId().equals(userId) && t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest(INVALID_CODE));
        token.markUsed(now);

        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(now);
        }

        return user;
    }

    @Transactional
    public void resendEmailVerification(UUID userId) {
        rateLimiter.consume(Action.EMAIL_RESEND, userId.toString());
        User user = userService.getActiveUser(userId);

        if (!user.isEmailVerified()) {
            sendEmailVerification(user);
        }
    }

    /**
     * Sends an 8-digit reset code by email (30 min, single use; a new request cancels the previous code).
     * Same response whether or not the account exists (no account enumeration).
     */
    @Transactional
    public void forgotPassword(String rawEmail, String clientIp) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.consume(Action.PASSWORD_FORGOT, clientIp);
        rateLimiter.consume(Action.PASSWORD_FORGOT, email);

        userRepository.findByEmail(email).filter(User::isActive).ifPresent(user -> {
            Instant now = clock.instant();
            oneTimeTokenRepository.invalidateUnused(user.getId(), OneTimeTokenType.PASSWORD_RESET, now);
            String code = tokenService.generateVerificationCode();
            oneTimeTokenRepository.save(new OneTimeToken(user, OneTimeTokenType.PASSWORD_RESET,
                    tokenService.hashPasswordResetCode(user.getId(), code), now.plus(properties.passwordResetTtl())));
            emailSender.sendPasswordReset(user, code);
        });
    }

    /**
     * Checks the emailed code, sets the new password and signs out every session of the account. An unknown email
     * and a wrong or expired code get the same answer. Attempts are limited per email and per IP, and the code is
     * cancelled after {@code PASSWORD_RESET_FAILURE} wrong codes (that cancellation must survive the error, hence
     * no rollback on {@link ApiException}).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(ResetPasswordRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        rateLimiter.consume(Action.PASSWORD_RESET, clientIp);
        rateLimiter.consume(Action.PASSWORD_RESET, email);
        passwordPolicy.validate(request.newPassword(), request.confirmPassword());

        Instant now = clock.instant();
        User user = userRepository.findByEmail(email).filter(User::isActive)
                .orElseThrow(() -> ApiException.badRequest(INVALID_CODE));
        Optional<OneTimeToken> token = oneTimeTokenRepository
                .findByTokenHashAndType(tokenService.hashPasswordResetCode(user.getId(), request.code()),
                        OneTimeTokenType.PASSWORD_RESET)
                .filter(t -> t.getUser().getId().equals(user.getId()) && t.isUsable(now));
        if (token.isEmpty()) {
            if (rateLimiter.reachesLimit(Action.PASSWORD_RESET_FAILURE, user.getId().toString())) {
                oneTimeTokenRepository.invalidateUnused(user.getId(), OneTimeTokenType.PASSWORD_RESET, now);
            }
            throw ApiException.badRequest(INVALID_CODE);
        }
        token.get().markUsed(now);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
    }

    private record IssuedSession(AuthSession session, RefreshToken refreshToken) {
    }

    private AuthSession startSession(User user, UUID familyId) {
        return issueSession(user, familyId).session();
    }

    private IssuedSession issueSession(User user, UUID familyId) {
        Instant now = clock.instant();
        String rawRefreshToken = tokenService.generateOpaqueToken();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        RefreshToken refreshToken = refreshTokenRepository.save(
                new RefreshToken(user, familyId, tokenService.hash(rawRefreshToken), refreshExpiresAt));
        AuthSession session = new AuthSession(
                tokenService.issueAccessToken(user, now), rawRefreshToken, refreshExpiresAt, user);

        return new IssuedSession(session, refreshToken);
    }

    private void sendEmailVerification(User user) {
        Instant now = clock.instant();
        oneTimeTokenRepository.invalidateUnused(user.getId(), OneTimeTokenType.EMAIL_VERIFICATION, now);
        String code = tokenService.generateVerificationCode();
        oneTimeTokenRepository.save(new OneTimeToken(user, OneTimeTokenType.EMAIL_VERIFICATION,
                tokenService.hashVerificationCode(user.getId(), code), now.plus(properties.emailVerificationTtl())));
        emailSender.sendEmailVerification(user, code);
    }

    private String dummyHash() {
        if (dummyPasswordHash == null) {
            dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        }

        return dummyPasswordHash;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
