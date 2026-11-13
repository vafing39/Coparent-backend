package com.vynk.coparent_backend.event.entity;

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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ligne de l'historique d'un événement (cahier des charges, 5). Rattachée à la série ({@code seriesId}) et non à
 * l'événement : elle reste quand une série est arrêtée, détachée ou supprimée.
 */
@Entity
@Table(name = "event_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventChange {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    @Column(name = "series_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seriesId;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", columnDefinition = "binary(16)")
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventChangeAction action;

    @Enumerated(EnumType.STRING)
    private EventScope scope;

    private LocalDate occurrenceDate;

    @Column(length = 200)
    private String fields;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public EventChange(Family family, UUID seriesId, User author, EventChangeAction action, EventScope scope,
                       LocalDate occurrenceDate, Collection<EventField> fields, Instant now) {
        this.family = family;
        this.seriesId = seriesId;
        this.author = author;
        this.action = action;
        this.scope = scope;
        this.occurrenceDate = occurrenceDate;
        this.fields = fields == null || fields.isEmpty()
                ? null : fields.stream().map(Enum::name).collect(Collectors.joining(","));
        this.createdAt = now;
    }

    public List<EventField> fieldList() {
        return fields == null ? List.of() : Arrays.stream(fields.split(",")).map(EventField::valueOf).toList();
    }
}
