package com.vynk.coparent_backend.event.entity;

import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Événement de la famille. {@code startsAt}/{@code endsAt} sont des instants UTC ; une journée entière va de
 * minuit à minuit, heure de Paris. Un événement privé n'est visible que par son auteur.
 */
@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

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
    @JoinColumn(name = "author_id", columnDefinition = "binary(16)")
    private User author;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @ManyToMany
    @JoinTable(name = "event_children",
            joinColumns = @JoinColumn(name = "event_id", columnDefinition = "binary(16)"),
            inverseJoinColumns = @JoinColumn(name = "child_id", columnDefinition = "binary(16)"))
    private Set<Child> children = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean allDay;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Column(length = 200)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventReminder reminder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventVisibility visibility;

    /** Null : événement unique. Sinon, la date de l'événement est la première séance de la série. */
    @Embedded
    private EventRepeat repeat;

    /** Séances retirées de la série (supprimées seules, ou remplacées par un événement modifié). */
    @ElementCollection
    @CollectionTable(name = "event_exceptions",
            joinColumns = @JoinColumn(name = "event_id", columnDefinition = "binary(16)"))
    @Column(name = "occurrence_date")
    private Set<LocalDate> skippedDates = new HashSet<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Event(Family family, User author) {
        this.family = family;
        this.author = author;
    }

    /** Remplace tout le contenu de l'événement (création ou modification). */
    public void update(String title, EventType type, Set<Child> children, boolean allDay, Instant startsAt,
                       Instant endsAt, String location, EventReminder reminder, EventVisibility visibility,
                       Instant now) {
        this.title = title;
        this.type = type;
        this.children.clear();
        this.children.addAll(children);
        this.allDay = allDay;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.location = location;
        this.reminder = reminder;
        this.visibility = visibility;
        this.updatedAt = now;
    }

    public boolean isRepeating() {
        return repeat != null && repeat.getFrequency() != null;
    }

    /** Règle de répétition (null : événement unique). */
    public void repeat(EventRepeat repeat, Instant now) {
        this.repeat = repeat;
        this.updatedAt = now;
    }

    /** Retire une séance de la série. */
    public void skip(LocalDate occurrence, Instant now) {
        this.skippedDates.add(occurrence);
        this.updatedAt = now;
    }

    /** La série repart de zéro (modifiée en entier) : plus aucune séance retirée. */
    public void clearSkippedDates() {
        this.skippedDates.clear();
    }

    /** Arrête la série : plus aucune séance après {@code lastDay}. */
    public void endRepeatOn(LocalDate lastDay, Instant now) {
        this.repeat = repeat.endingOn(lastDay);
        this.updatedAt = now;
    }

    /** Visible par {@code userId} : partagé, ou privé et écrit par lui. */
    public boolean isVisibleTo(UUID userId) {
        return visibility == EventVisibility.SHARED || (author != null && author.getId().equals(userId));
    }
}
