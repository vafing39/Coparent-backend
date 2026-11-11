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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Une version du rythme de garde et des règles de vacances (étape 4). Une nouvelle version est proposée
 * sans écraser celle en vigueur. La colonne générée {@code active_family_id} (une seule version ACTIVE
 * par famille) n'est pas mappée.
 */
@Entity
@Table(name = "custody_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustodySchedule {

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
    private CustodyPattern pattern;

    /** CUSTOM only: one letter per night, A or B. */
    @Column(length = 56)
    private String customSequence;

    /** First handover; its weekday is the handover day. */
    @Column(nullable = false)
    private LocalDate startDate;

    /** Europe/Paris wall-clock time (not UTC): 17:50 stays 17:50 across DST changes. */
    @Column(nullable = false)
    private LocalTime handoverTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole startingParent;

    /** Who has the first half of the short holidays in even years (order swapped in odd years). */
    @Enumerated(EnumType.STRING)
    private FamilyRole holidayFirstHalfEvenYears;

    @Enumerated(EnumType.STRING)
    private SummerSplit summerSplit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleStatus status = ScheduleStatus.PROPOSED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposed_by", columnDefinition = "binary(16)")
    private User proposedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", columnDefinition = "binary(16)")
    private User decidedBy;

    private Instant decidedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public CustodySchedule(Family family, User proposedBy) {
        this.family = family;
        this.proposedBy = proposedBy;
    }

    public void define(CustodyPattern pattern, String customSequence, LocalDate startDate, LocalTime handoverTime,
                       FamilyRole startingParent, FamilyRole holidayFirstHalfEvenYears, SummerSplit summerSplit) {
        this.pattern = pattern;
        this.customSequence = customSequence;
        this.startDate = startDate;
        this.handoverTime = handoverTime;
        this.startingParent = startingParent;
        this.holidayFirstHalfEvenYears = holidayFirstHalfEvenYears;
        this.summerSplit = summerSplit;
    }
}
