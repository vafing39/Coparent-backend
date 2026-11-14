package com.vynk.coparent_backend.expense.entity;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyRole;
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
 * Un virement déclaré par le parent qui doit le solde ({@code payerRole}). L'autre parent confirme la réception,
 * ou indique qu'il ne l'a pas encore reçu (le virement reste ouvert). L'app n'effectue aucun paiement.
 */
@Entity
@Table(name = "settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole payerRole;

    @Column(nullable = false)
    private int amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status = SettlementStatus.DECLARED;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant declaredAt;

    private Instant answeredAt;

    public Settlement(Family family, FamilyRole payerRole, int amountCents) {
        this.family = family;
        this.payerRole = payerRole;
        this.amountCents = amountCents;
    }

    public void confirm(Instant now) {
        requireOpen();
        this.status = SettlementStatus.CONFIRMED;
        this.answeredAt = now;
    }

    public void notReceived(Instant now) {
        if (status != SettlementStatus.DECLARED) {
            throw ApiException.conflict("Ce virement n'attend plus de réponse.");
        }
        this.status = SettlementStatus.NOT_RECEIVED;
        this.answeredAt = now;
    }

    public void cancel(Instant now) {
        requireOpen();
        this.status = SettlementStatus.CANCELLED;
        this.answeredAt = now;
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw ApiException.conflict("Ce virement n'attend plus de réponse.");
        }
    }
}
