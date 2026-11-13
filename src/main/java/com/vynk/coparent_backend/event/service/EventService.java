package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.event.dto.EventChildResponse;
import com.vynk.coparent_backend.event.dto.EventRequest;
import com.vynk.coparent_backend.event.dto.EventResponse;
import com.vynk.coparent_backend.event.entity.Event;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import com.vynk.coparent_backend.event.repository.EventRepository;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Événements (cahier des charges, 5), sans répétition pour l'instant. Le serveur applique la visibilité : un
 * événement privé n'est jamais renvoyé à l'autre parent, et lui répond 404 comme s'il n'existait pas.
 * Les dates arrivent et repartent en heure de Paris ; elles sont stockées en instants UTC.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int MAX_DAYS = 92;
    static final String NOT_FOUND = "Événement introuvable.";
    static final String CHILD_NOT_FOUND = "Enfant introuvable dans cette famille.";

    private final FamilyAccess familyAccess;
    private final ChildRepository childRepository;
    private final EventRepository repository;
    private final Clock clock;

    /** Événements visibles qui touchent les jours {@code from} à {@code to} inclus (Paris). */
    @Transactional(readOnly = true)
    public List<EventResponse> list(UUID familyId, UUID userId, LocalDate from, LocalDate to) {
        familyAccess.requireMember(familyId, userId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.badRequest(
                    "Période invalide : la date de fin suit la date de début, " + MAX_DAYS + " jours au plus.");
        }
        return repository.findVisible(familyId, userId, startOfDay(from), startOfDay(to.plusDays(1)),
                        EventVisibility.SHARED)
                .stream()
                .map(event -> toResponse(event, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse get(UUID familyId, UUID userId, UUID eventId) {
        familyAccess.requireMember(familyId, userId);
        return toResponse(requireVisible(familyId, userId, eventId), userId);
    }

    @Transactional
    public EventResponse create(UUID familyId, UUID userId, EventRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = new Event(me.getFamily(), me.getUser());
        apply(event, familyId, request);
        return toResponse(repository.save(event), userId);
    }

    @Transactional
    public EventResponse update(UUID familyId, UUID userId, UUID eventId, EventRequest request) {
        familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        apply(event, familyId, request);
        repository.flush();
        return toResponse(event, userId);
    }

    @Transactional
    public void delete(UUID familyId, UUID userId, UUID eventId) {
        familyAccess.requireMember(familyId, userId);
        repository.delete(requireVisible(familyId, userId, eventId));
    }

    private Event requireVisible(UUID familyId, UUID userId, UUID eventId) {
        return repository.findByIdAndFamilyId(eventId, familyId)
                .filter(event -> event.isVisibleTo(userId))
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private void apply(Event event, UUID familyId, EventRequest request) {
        Set<Child> children = new LinkedHashSet<>();
        for (UUID childId : request.childIds()) {
            children.add(childRepository.findByIdAndFamilyId(childId, familyId)
                    .orElseThrow(() -> ApiException.badRequest(CHILD_NOT_FOUND)));
        }
        Instant startsAt;
        Instant endsAt;
        if (request.allDay()) {
            startsAt = startOfDay(request.date());
            endsAt = startOfDay(request.date().plusDays(1));
        } else {
            if (request.startTime() == null || request.endTime() == null) {
                throw ApiException.badRequest("Indiquez l'heure de début et l'heure de fin.");
            }
            if (!request.endTime().isAfter(request.startTime())) {
                throw ApiException.badRequest("L'heure de fin doit suivre l'heure de début.");
            }
            startsAt = request.date().atTime(request.startTime()).atZone(PARIS).toInstant();
            endsAt = request.date().atTime(request.endTime()).atZone(PARIS).toInstant();
        }
        String location = request.location() == null || request.location().isBlank()
                ? null : request.location().strip();
        event.update(request.title().strip(), request.type(), children, request.allDay(), startsAt, endsAt,
                location, request.reminder(), request.visibility(), clock.instant());
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(PARIS).toInstant();
    }

    private static EventResponse toResponse(Event event, UUID userId) {
        ZonedDateTime start = event.getStartsAt().atZone(PARIS);
        ZonedDateTime end = event.getEndsAt().atZone(PARIS);
        List<EventChildResponse> children = event.getChildren().stream()
                .sorted(Comparator.comparing(Child::getBirthDate).thenComparing(Child::getCreatedAt))
                .map(child -> new EventChildResponse(child.getId(), child.getFirstName()))
                .toList();
        boolean mine = event.getAuthor() != null && event.getAuthor().getId().equals(userId);
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getType(),
                children,
                event.isAllDay(),
                start.toLocalDate(),
                event.isAllDay() ? null : start.toLocalTime(),
                event.isAllDay() ? null : end.toLocalTime(),
                event.getLocation(),
                event.getReminder(),
                event.getVisibility(),
                event.getAuthor() == null ? null : event.getAuthor().getFirstName(),
                mine,
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
