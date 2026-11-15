package com.vynk.coparent_backend.family.entity;

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

/**
 * Un proche de la famille élargie (grand-parent, beau-parent, oncle ou tante). Proposé par un parent, validé par
 * l'autre, invité par email, puis actif : il ne voit que les publications « Famille » du fil. Ce n'est pas un
 * {@link FamilyMember} : aucune autre route de la famille ne lui est ouverte.
 */
@Entity
@Table(name = "family_relatives")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FamilyRelative {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    /** Le compte du proche, une fois qu'il a rejoint (null si ce compte a été supprimé). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "binary(16)")
    private User user;

    @Column(nullable = false, length = 100)
    private String firstName;

    /** Adresse de l'invitation ; effacée quand le proche ne fait plus partie de la famille. */
    @Column(length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RelativeRelation relation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole proposedByRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RelativeStatus status;

    @Column(length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    private Instant expiresAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant approvedAt;

    private Instant joinedAt;

    private Instant endedAt;

    public FamilyRelative(Family family, String firstName, String email, RelativeRelation relation,
                          FamilyRole proposedByRole) {
        this.family = family;
        this.firstName = firstName;
        this.email = email;
        this.relation = relation;
        this.proposedByRole = proposedByRole;
        this.status = RelativeStatus.PROPOSED;
    }

    public boolean isActive() {
        return status == RelativeStatus.ACTIVE;
    }

    /** Validé par l'autre parent (ou lien renouvelé) : un nouveau lien remplace le précédent. */
    public void invite(String tokenHash, Instant expiresAt, Instant now) {
        if (approvedAt == null) {
            this.approvedAt = now;
        }
        this.status = RelativeStatus.INVITED;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isInvitationUsable(Instant now) {
        return status == RelativeStatus.INVITED && expiresAt != null && now.isBefore(expiresAt);
    }

    public void join(User user, Instant now) {
        this.user = user;
        this.status = RelativeStatus.ACTIVE;
        this.joinedAt = now;
        this.tokenHash = null;
        this.expiresAt = null;
    }

    public void end(RelativeStatus status, Instant now) {
        this.status = status;
        this.endedAt = now;
        this.tokenHash = null;
        this.expiresAt = null;
        this.email = null;
    }
}
