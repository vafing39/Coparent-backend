package com.vynk.coparent_backend.exchange.entity;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.service.CustodyOverride;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Demande d'échange de garde (cahier des charges, 6). Le demandeur ({@code requesterRole}) prend les nuits
 * {@code takeFrom..takeTo} de l'autre parent et, en option, lui laisse {@code giveFrom..giveTo}. Seule une demande
 * PENDING reçoit une réponse ; acceptée, elle change le calcul de la garde.
 */
@Entity
@Table(name = "custody_exchanges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustodyExchange {

    static final String NOT_PENDING = "Cette demande n'attend plus de réponse.";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", columnDefinition = "binary(16)")
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole requesterRole;

    @Column(nullable = false)
    private LocalDate takeFrom;

    @Column(nullable = false)
    private LocalDate takeTo;

    private LocalDate giveFrom;

    private LocalDate giveTo;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExchangeStatus status = ExchangeStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant answeredAt;

    public CustodyExchange(Family family, User requestedBy, FamilyRole requesterRole, LocalDate takeFrom,
                           LocalDate takeTo, LocalDate giveFrom, LocalDate giveTo, String message) {
        this.family = family;
        this.requestedBy = requestedBy;
        this.requesterRole = requesterRole;
        this.takeFrom = takeFrom;
        this.takeTo = takeTo;
        this.giveFrom = giveFrom;
        this.giveTo = giveTo;
        this.message = message;
    }

    public boolean hasGive() {
        return giveFrom != null;
    }

    /** Dernière nuit concernée par l'échange. */
    public LocalDate lastNight() {
        return hasGive() && giveTo.isAfter(takeTo) ? giveTo : takeTo;
    }

    /** Vrai si l'une des deux périodes touche les nuits {@code from..to}. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return overlap(takeFrom, takeTo, from, to) || (hasGive() && overlap(giveFrom, giveTo, from, to));
    }

    /** Les nuits attribuées par cet échange, une fois accepté. */
    public List<CustodyOverride> overrides() {
        List<CustodyOverride> overrides = new ArrayList<>();
        overrides.add(new CustodyOverride(takeFrom, takeTo, requesterRole));
        if (hasGive()) {
            overrides.add(new CustodyOverride(giveFrom, giveTo, other(requesterRole)));
        }
        return overrides;
    }

    public void accept(Instant now) {
        answer(ExchangeStatus.ACCEPTED, now);
    }

    public void decline(Instant now) {
        answer(ExchangeStatus.DECLINED, now);
    }

    public void cancel(Instant now) {
        answer(ExchangeStatus.CANCELLED, now);
    }

    private void answer(ExchangeStatus newStatus, Instant now) {
        if (status != ExchangeStatus.PENDING) {
            throw ApiException.conflict(NOT_PENDING);
        }
        this.status = newStatus;
        this.answeredAt = now;
    }

    static boolean overlap(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }

    private static FamilyRole other(FamilyRole role) {
        return role == FamilyRole.PARENT_A ? FamilyRole.PARENT_B : FamilyRole.PARENT_A;
    }
}
