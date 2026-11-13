package com.vynk.coparent_backend.todo.entity;

import com.vynk.coparent_backend.family.entity.Family;
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

/** Demande qui attend la réponse d'un parent ({@code assignee}). Répondue ({@code resolvedAt}), elle quitte la liste. */
@Entity
@Table(name = "to_do_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToDoItem {

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
    private ToDoKind kind;

    @Column(nullable = false, length = 150)
    private String subject;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", columnDefinition = "binary(16)")
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_id", nullable = false, columnDefinition = "binary(16)")
    private User assignee;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant lastNudgedAt;

    private Instant resolvedAt;

    public ToDoItem(Family family, ToDoKind kind, String subject, User requestedBy, User assignee) {
        this.family = family;
        this.kind = kind;
        this.subject = subject;
        this.requestedBy = requestedBy;
        this.assignee = assignee;
    }

    /** « Relancer » : la date de la dernière relance, une par jour au maximum (vérifié par le service). */
    public void nudge(Instant now) {
        this.lastNudgedAt = now;
    }
}
