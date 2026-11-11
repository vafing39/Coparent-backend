package com.vynk.coparent_backend.auth.entity;

import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh token of a session. Every refresh rotates the token: the old one is revoked and points to its
 * replacement, and all tokens of a session share the same {@code familyId}. Presenting an already-replaced
 * token means it was stolen or replayed, so the whole family is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, columnDefinition = "binary(16)")
    private User user;

    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID familyId;

    /** SHA-256 (hex) of the token; the raw value is only ever sent to the client. */
    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(columnDefinition = "binary(16)")
    private UUID replacedById;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken(User user, UUID familyId, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean wasRotated() {
        return replacedById != null;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public void rotateTo(RefreshToken replacement, Instant now) {
        revoke(now);
        replacedById = replacement.getId();
    }
}
