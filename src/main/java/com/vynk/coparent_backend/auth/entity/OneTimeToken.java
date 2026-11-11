package com.vynk.coparent_backend.auth.entity;

import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Single-use link token: email verification (24 h) or password reset (30 min). */
@Entity
@Table(name = "one_time_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OneTimeToken {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, columnDefinition = "binary(16)")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OneTimeTokenType type;

    /** SHA-256 (hex) of the token sent in the link. */
    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public OneTimeToken(User user, OneTimeTokenType type, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.type = type;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }
}
