package com.vynk.coparent_backend.family.entity;

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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import com.vynk.coparent_backend.user.entity.User;

import java.time.Instant;
import java.util.UUID;

/** Invitation de l'autre parent (étape 5) : lien à usage unique ; seul le SHA-256 du jeton est stocké. */
@Entity
@Table(name = "family_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyInvitation {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", columnDefinition = "binary(16)")
    private User invitedBy;

    @Column(nullable = false, length = 100)
    private String inviteeFirstName;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by", columnDefinition = "binary(16)")
    private User acceptedBy;

    /** Set when a newer link replaces this one. */
    private Instant revokedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public FamilyInvitation(Family family, User invitedBy, String inviteeFirstName, String tokenHash,
                            Instant expiresAt) {
        this.family = family;
        this.invitedBy = invitedBy;
        this.inviteeFirstName = inviteeFirstName;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && invitedBy != null && now.isBefore(expiresAt);
    }

    public void accept(User user, Instant now) {
        acceptedBy = user;
        acceptedAt = now;
    }
}
