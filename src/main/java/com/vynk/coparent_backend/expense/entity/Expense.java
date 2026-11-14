package com.vynk.coparent_backend.expense.entity;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Une dépense payée par un parent ({@code payerRole}) et partagée : le payeur garde {@code payerSharePercent} %,
 * l'autre parent lui doit le reste ({@link #owedCents()}). L'autre parent l'accepte ou la conteste ; l'auteur la
 * corrige (elle repasse en attente) ou la retire.
 */
@Entity
@Table(name = "expenses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense {

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
    @JoinColumn(name = "created_by", columnDefinition = "binary(16)")
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole authorRole;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(nullable = false)
    private int amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole payerRole;

    @Column(nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false, columnDefinition = "tinyint")
    private int payerSharePercent;

    @ManyToMany
    @JoinTable(name = "expense_children",
            joinColumns = @JoinColumn(name = "expense_id", columnDefinition = "binary(16)"),
            inverseJoinColumns = @JoinColumn(name = "child_id", columnDefinition = "binary(16)"))
    private Set<Child> children = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status = ExpenseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private ContestReason contestReason;

    @Column(length = 500)
    private String contestNote;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant answeredAt;

    public Expense(Family family, User createdBy, FamilyRole authorRole) {
        this.family = family;
        this.createdBy = createdBy;
        this.authorRole = authorRole;
    }

    /** Saisie ou correction : la dépense (re)passe en attente, sans contestation. */
    public void fill(String label, int amountCents, ExpenseCategory category, FamilyRole payerRole, LocalDate paidOn,
                     int payerSharePercent, Collection<Child> children) {
        if (status != ExpenseStatus.PENDING && status != ExpenseStatus.CONTESTED) {
            throw ApiException.conflict("Cette dépense ne peut plus être modifiée.");
        }
        this.label = label;
        this.amountCents = amountCents;
        this.category = category;
        this.payerRole = payerRole;
        this.paidOn = paidOn;
        this.payerSharePercent = payerSharePercent;
        this.children.clear();
        this.children.addAll(children);
        this.status = ExpenseStatus.PENDING;
        this.contestReason = null;
        this.contestNote = null;
        this.answeredAt = null;
    }

    public void accept(Instant now) {
        requirePending();
        this.status = ExpenseStatus.VALIDATED;
        this.answeredAt = now;
    }

    public void contest(ContestReason reason, String note, Instant now) {
        requirePending();
        this.status = ExpenseStatus.CONTESTED;
        this.contestReason = reason;
        this.contestNote = note;
        this.answeredAt = now;
    }

    public void withdraw() {
        if (status != ExpenseStatus.PENDING && status != ExpenseStatus.CONTESTED) {
            throw ApiException.conflict("Une dépense validée ne peut plus être retirée.");
        }
        this.status = ExpenseStatus.WITHDRAWN;
    }

    /** Ce que l'autre parent doit au payeur pour cette dépense. */
    public int owedCents() {
        return owedCents(amountCents, payerSharePercent);
    }

    /** Part de l'autre parent, arrondie au centime (au plus proche, 0,5 vers le haut). */
    public static int owedCents(int amountCents, int payerSharePercent) {
        return BigDecimal.valueOf((long) amountCents * (100 - payerSharePercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private void requirePending() {
        if (status != ExpenseStatus.PENDING) {
            throw ApiException.conflict("Cette dépense n'attend plus de réponse.");
        }
    }
}
