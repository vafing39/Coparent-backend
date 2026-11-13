package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.event.dto.EventChildResponse;
import com.vynk.coparent_backend.event.dto.EventRequest;
import com.vynk.coparent_backend.event.dto.EventResponse;
import com.vynk.coparent_backend.event.dto.EventScope;
import com.vynk.coparent_backend.event.dto.RepeatPreviewDate;
import com.vynk.coparent_backend.event.dto.RepeatPreviewRequest;
import com.vynk.coparent_backend.event.dto.RepeatRequest;
import com.vynk.coparent_backend.event.dto.RepeatResponse;
import com.vynk.coparent_backend.event.entity.Event;
import com.vynk.coparent_backend.event.entity.EventRepeat;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.repository.EventRepository;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.service.CustodyCalculator;
import com.vynk.coparent_backend.family.service.CustodyService;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.family.service.SchoolHolidays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Événements (cahier des charges, 5). Le serveur applique la visibilité : un événement privé n'est jamais renvoyé
 * à l'autre parent, et lui répond 404 comme s'il n'existait pas. Les dates arrivent et repartent en heure de
 * Paris ; elles sont stockées en instants UTC.
 * <p>
 * Un événement répété porte sa règle ; ses séances sont calculées à la lecture ({@link EventOccurrences}), pour
 * suivre la garde (« seulement quand les enfants sont chez ») et les vacances scolaires. Modifier ou supprimer
 * une séance : ONE la retire de la série (une modification devient un événement unique) ; FOLLOWING et ALL
 * arrêtent la série la veille et, pour une modification, en commencent une nouvelle. Les séances passées ne
 * changent jamais.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int MAX_DAYS = 92;
    static final String NOT_FOUND = "Événement introuvable.";
    static final String CHILD_NOT_FOUND = "Enfant introuvable dans cette famille.";
    static final String NOT_AN_OCCURRENCE = "Cette séance n'existe pas dans la série.";
    static final String PAST = "Les séances passées ne sont pas modifiées.";

    private final FamilyAccess familyAccess;
    private final ChildRepository childRepository;
    private final EventRepository repository;
    private final CustodyService custodyService;
    private final SchoolHolidays schoolHolidays;
    private final Clock clock;

    /** Événements et séances visibles qui touchent les jours {@code from} à {@code to} inclus (Paris). */
    @Transactional(readOnly = true)
    public List<EventResponse> list(UUID familyId, UUID userId, LocalDate from, LocalDate to) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.badRequest(
                    "Période invalide : la date de fin suit la date de début, " + MAX_DAYS + " jours au plus.");
        }
        List<EventResponse> result = new ArrayList<>();
        repository.findVisible(familyId, userId, startOfDay(from), startOfDay(to.plusDays(1)), EventVisibility.SHARED)
                .forEach(event -> result.add(toResponse(event, userId, localDate(event))));

        Filters filters = new Filters(me);
        for (Event series : repository.findVisibleSeries(familyId, userId, startOfDay(to.plusDays(1)), from,
                EventVisibility.SHARED)) {
            for (LocalDate date : occurrences(series, filters, from, to)) {
                result.add(toResponse(series, userId, date));
            }
        }
        result.sort(Comparator.comparing(EventResponse::date)
                .thenComparing(r -> r.allDay() ? LocalTime.MIN : r.startTime())
                .thenComparing(EventResponse::createdAt));
        return result;
    }

    /** Nombre de dates de l'aperçu d'une répétition. */
    static final int PREVIEW_SIZE = 8;

    /**
     * Les prochaines dates d'une répétition avant l'enregistrement, prévues ou non (vacances, enfants chez l'autre
     * parent), pour l'aperçu du formulaire. S'arrête à la fin choisie.
     */
    @Transactional(readOnly = true)
    public List<RepeatPreviewDate> preview(UUID familyId, UUID userId, RepeatPreviewRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        EventRepeat repeat = toRepeat(me, request.date(), request.repeat());
        Optional<CustodyCalculator> custody = custodyService.currentCalculator(me);
        List<SchoolHolidays.Holiday> holidays = schoolHolidays.forZone(me.getFamily().getVacationZone());
        List<RepeatPreviewDate> result = new ArrayList<>();
        int sessions = 0;
        for (int step = 0; step < EventOccurrences.MAX_STEPS && result.size() < PREVIEW_SIZE; step++) {
            LocalDate date = EventOccurrences.dateAt(request.date(), repeat, step);
            if (repeat.getUntil() != null && date.isAfter(repeat.getUntil())) {
                break;
            }
            FamilyRole parent = custody.map(c -> c.parentOn(date)).orElse(null);
            RepeatPreviewDate.Skipped skipped = null;
            if (repeat.skipsHolidays() && inHolidays(holidays, date)) {
                skipped = RepeatPreviewDate.Skipped.HOLIDAYS;
            } else if (repeat.getOnlyWith() != null && parent != null && parent != repeat.getOnlyWith()) {
                skipped = RepeatPreviewDate.Skipped.OTHER_PARENT;
            }
            result.add(new RepeatPreviewDate(date, parent, skipped));
            if (skipped == null && repeat.getCount() != null && ++sessions == repeat.getCount()) {
                break; // dernière séance
            }
        }
        return result;
    }

    private static boolean inHolidays(List<SchoolHolidays.Holiday> holidays, LocalDate date) {
        return holidays.stream().anyMatch(h -> !date.isBefore(h.start()) && date.isBefore(h.resume()));
    }

    /** Un événement ; pour une série, la séance du {@code date} (par défaut la première). */
    @Transactional(readOnly = true)
    public EventResponse get(UUID familyId, UUID userId, UUID eventId, LocalDate date) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        if (!event.isRepeating() || date == null) {
            return toResponse(event, userId, localDate(event));
        }
        requireOccurrence(event, new Filters(me), date);
        return toResponse(event, userId, date);
    }

    @Transactional
    public EventResponse create(UUID familyId, UUID userId, EventRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = newEvent(me, familyId, request);
        return toResponse(event, userId, localDate(event));
    }

    /**
     * Modifie un événement. Pour une série, {@code scope} et {@code date} (la séance ouverte) disent quelles
     * séances changent ; la réponse est l'événement qui porte désormais la modification.
     */
    @Transactional
    public EventResponse update(UUID familyId, UUID userId, UUID eventId, EventScope scope, LocalDate date,
                                EventRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        if (!event.isRepeating()) {
            apply(event, me, familyId, request);
            repository.flush();
            return toResponse(event, userId, localDate(event));
        }
        Filters filters = new Filters(me);
        LocalDate split = splitDate(event, filters, scope, date);
        Instant now = clock.instant();
        if (scope == EventScope.ONE) {
            event.skip(split, now);
            Event single = newEvent(me, familyId, withoutRepeat(request));
            return toResponse(single, userId, localDate(single));
        }
        if (occurrences(event, filters, localDate(event), split.minusDays(1)).isEmpty()) {
            // Aucune séance avant : toute la série change.
            event.clearSkippedDates();
            apply(event, me, familyId, request);
            repository.flush();
            return toResponse(event, userId, localDate(event));
        }
        if (request.date().isBefore(today())) {
            throw ApiException.conflict(PAST);
        }
        event.endRepeatOn(split.minusDays(1), now);
        Event next = newEvent(me, familyId, request);
        return toResponse(next, userId, localDate(next));
    }

    /** Supprime un événement ; pour une série, les séances que {@code scope} désigne à partir de {@code date}. */
    @Transactional
    public void delete(UUID familyId, UUID userId, UUID eventId, EventScope scope, LocalDate date) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        if (!event.isRepeating()) {
            repository.delete(event);
            return;
        }
        Filters filters = new Filters(me);
        LocalDate split = splitDate(event, filters, scope, date);
        Instant now = clock.instant();
        if (scope == EventScope.ONE) {
            event.skip(split, now);
        } else if (occurrences(event, filters, localDate(event), split.minusDays(1)).isEmpty()) {
            repository.delete(event);
        } else {
            event.endRepeatOn(split.minusDays(1), now);
        }
    }

    /** La première séance concernée : celle ouverte (ONE, FOLLOWING), ou la prochaine à venir (ALL). */
    private LocalDate splitDate(Event event, Filters filters, EventScope scope, LocalDate date) {
        LocalDate today = today();
        if (scope == null) {
            throw ApiException.badRequest("Indiquez les séances concernées.");
        }
        if (scope == EventScope.ALL) {
            return occurrences(event, filters, today, today.plusYears(20)).stream().findFirst()
                    .orElseThrow(() -> ApiException.conflict("Cette série n'a plus de séance à venir."));
        }
        if (date == null) {
            throw ApiException.badRequest("Indiquez la séance concernée.");
        }
        requireOccurrence(event, filters, date);
        if (date.isBefore(today)) {
            throw ApiException.conflict(PAST);
        }
        return date;
    }

    private void requireOccurrence(Event event, Filters filters, LocalDate date) {
        if (occurrences(event, filters, date, date).isEmpty()) {
            throw ApiException.notFound(NOT_AN_OCCURRENCE);
        }
    }

    private List<LocalDate> occurrences(Event series, Filters filters, LocalDate from, LocalDate to) {
        return EventOccurrences.between(localDate(series), series.getRepeat(), series.getSkippedDates(),
                filters.of(series.getRepeat()), from, to);
    }

    private Event newEvent(FamilyMember me, UUID familyId, EventRequest request) {
        Event event = new Event(me.getFamily(), me.getUser());
        apply(event, me, familyId, request);
        return repository.save(event);
    }

    private static EventRequest withoutRepeat(EventRequest r) {
        return new EventRequest(r.title(), r.type(), r.childIds(), r.date(), r.allDay(), r.startTime(), r.endTime(),
                r.location(), r.reminder(), r.visibility(), null);
    }

    private Event requireVisible(UUID familyId, UUID userId, UUID eventId) {
        return repository.findByIdAndFamilyId(eventId, familyId)
                .filter(event -> event.isVisibleTo(userId))
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private void apply(Event event, FamilyMember me, UUID familyId, EventRequest request) {
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
        Instant now = clock.instant();
        event.update(request.title().strip(), request.type(), children, request.allDay(), startsAt, endsAt,
                location, request.reminder(), request.visibility(), now);
        event.repeat(toRepeat(me, request.date(), request.repeat()), now);
    }

    private EventRepeat toRepeat(FamilyMember me, LocalDate first, RepeatRequest request) {
        if (request == null) {
            return null;
        }
        if ((request.end() == RepeatEnd.COUNT) != (request.count() != null)) {
            throw ApiException.badRequest(request.end() == RepeatEnd.COUNT
                    ? "Indiquez le nombre de séances."
                    : "Le nombre de séances ne concerne que la fin « après N séances ».");
        }
        if (request.onlyWith() != null && custodyService.currentCalculator(me).isEmpty()) {
            throw ApiException.badRequest(
                    "Le filtre par parent suit le calendrier de garde : définissez d'abord un rythme.");
        }
        LocalDate until = request.end() == RepeatEnd.SCHOOL_YEAR ? schoolYearEnd(me, first) : null;
        return new EventRepeat(request.frequency(), request.onlyWith(), request.skipHolidays(), request.end(),
                request.count(), until);
    }

    /** Dernier jour de classe avant l'été qui suit {@code first} (sans dates connues : le 30 juin). */
    private LocalDate schoolYearEnd(FamilyMember me, LocalDate first) {
        return schoolHolidays.forZone(me.getFamily().getVacationZone()).stream()
                .filter(h -> h.kind() == SchoolHolidays.Kind.SUMMER && h.start().isAfter(first))
                .map(h -> h.start().minusDays(1))
                .min(Comparator.naturalOrder())
                .orElse(LocalDate.of(first.getMonthValue() >= 7 ? first.getYear() + 1 : first.getYear(), 6, 30));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(PARIS));
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(PARIS).toInstant();
    }

    private static LocalDate localDate(Event event) {
        return event.getStartsAt().atZone(PARIS).toLocalDate();
    }

    /** Filtres d'une série (vacances de la zone, garde calculée une fois par requête). */
    private final class Filters {
        private final FamilyMember me;
        private Optional<CustodyCalculator> calculator;

        Filters(FamilyMember me) {
            this.me = me;
        }

        Predicate<LocalDate> of(EventRepeat repeat) {
            Predicate<LocalDate> kept = date -> true;
            if (repeat.skipsHolidays()) {
                List<SchoolHolidays.Holiday> holidays = schoolHolidays.forZone(me.getFamily().getVacationZone());
                kept = kept.and(date -> !inHolidays(holidays, date));
            }
            if (repeat.getOnlyWith() != null) {
                if (calculator == null) {
                    calculator = custodyService.currentCalculator(me);
                }
                // Sans rythme, le filtre ne peut pas s'appliquer : les séances restent.
                if (calculator.isPresent()) {
                    CustodyCalculator custody = calculator.get();
                    kept = kept.and(date -> custody.parentOn(date) == repeat.getOnlyWith());
                }
            }
            return kept;
        }
    }

    private static EventResponse toResponse(Event event, UUID userId, LocalDate date) {
        LocalTime start = event.getStartsAt().atZone(PARIS).toLocalTime();
        LocalTime end = event.getEndsAt().atZone(PARIS).toLocalTime();
        List<EventChildResponse> children = event.getChildren().stream()
                .sorted(Comparator.comparing(Child::getBirthDate).thenComparing(Child::getCreatedAt))
                .map(child -> new EventChildResponse(child.getId(), child.getFirstName()))
                .toList();
        boolean mine = event.getAuthor() != null && event.getAuthor().getId().equals(userId);
        EventRepeat repeat = event.isRepeating() ? event.getRepeat() : null;
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getType(),
                children,
                event.isAllDay(),
                date,
                event.isAllDay() ? null : start,
                event.isAllDay() ? null : end,
                event.getLocation(),
                event.getReminder(),
                event.getVisibility(),
                event.getAuthor() == null ? null : event.getAuthor().getFirstName(),
                mine,
                repeat == null ? null : new RepeatResponse(repeat.getFrequency(), repeat.getOnlyWith(),
                        repeat.skipsHolidays(), repeat.getEnd(), repeat.getCount(), localDate(event),
                        repeat.getUntil()),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
