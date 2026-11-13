package com.vynk.coparent_backend.event.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.event.dto.EventChangeResponse;
import com.vynk.coparent_backend.event.dto.EventChildResponse;
import com.vynk.coparent_backend.event.dto.EventRequest;
import com.vynk.coparent_backend.event.dto.EventResponse;
import com.vynk.coparent_backend.event.dto.RepeatDay;
import com.vynk.coparent_backend.event.dto.RepeatPreviewDate;
import com.vynk.coparent_backend.event.dto.RepeatPreviewRequest;
import com.vynk.coparent_backend.event.dto.RepeatRequest;
import com.vynk.coparent_backend.event.dto.RepeatResponse;
import com.vynk.coparent_backend.event.entity.Event;
import com.vynk.coparent_backend.event.entity.EventChange;
import com.vynk.coparent_backend.event.entity.EventChangeAction;
import com.vynk.coparent_backend.event.entity.EventField;
import com.vynk.coparent_backend.event.entity.EventRepeat;
import com.vynk.coparent_backend.event.entity.EventRepeatDay;
import com.vynk.coparent_backend.event.entity.EventScope;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import com.vynk.coparent_backend.event.entity.RepeatEnd;
import com.vynk.coparent_backend.event.entity.RepeatFrequency;
import com.vynk.coparent_backend.event.entity.RepeatMonthMode;
import com.vynk.coparent_backend.event.entity.RepeatUnit;
import com.vynk.coparent_backend.event.repository.EventChangeRepository;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
 * <p>
 * Chaque création, modification ou suppression ajoute une ligne à l'historique de la série ({@code seriesId}),
 * que l'on lit depuis n'importe lequel de ses événements visibles.
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
    private final EventChangeRepository changeRepository;
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
        Rule rule = toRule(me, request.date(), null, request.repeat());
        EventRepeat repeat = rule.repeat();
        Optional<CustodyCalculator> custody = custodyService.currentCalculator(me);
        List<SchoolHolidays.Holiday> holidays = schoolHolidays.forZone(me.getFamily().getVacationZone());
        List<RepeatPreviewDate> result = new ArrayList<>();
        int sessions = 0;
        Iterator<LocalDate> dates = EventOccurrences.candidates(request.date(), repeat, rule.dayOfWeeks());
        for (int step = 0; step < EventOccurrences.MAX_STEPS && result.size() < PREVIEW_SIZE; step++) {
            LocalDate date = dates.next();
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

    /** Historique de l'événement et de sa série, le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<EventChangeResponse> history(UUID familyId, UUID userId, UUID eventId) {
        familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        return changeRepository.findByFamilyIdAndSeriesIdOrderByCreatedAtDescIdDesc(familyId, event.getSeriesId())
                .stream()
                .map(change -> new EventChangeResponse(
                        change.getId(),
                        change.getAction(),
                        change.getScope(),
                        change.getOccurrenceDate(),
                        change.fieldList(),
                        change.getAuthor() == null ? null : change.getAuthor().getFirstName(),
                        change.getAuthor() != null && change.getAuthor().getId().equals(userId),
                        change.getCreatedAt()))
                .toList();
    }

    @Transactional
    public EventResponse create(UUID familyId, UUID userId, EventRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = newEvent(me, familyId, null, request);
        log(me, event.getSeriesId(), EventChangeAction.CREATED, null, null, Set.of());
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
            Set<EventField> changed = changes(toResponse(event, userId, localDate(event)), request, true);
            apply(event, me, familyId, request);
            repository.flush();
            log(me, event.getSeriesId(), EventChangeAction.UPDATED, null, null, changed);
            return toResponse(event, userId, localDate(event));
        }
        Filters filters = new Filters(me);
        LocalDate split = splitDate(event, filters, scope, date);
        if (scope == EventScope.ALL && date != null && !date.equals(split)) {
            // La séance ouverte peut être plus loin que la prochaine : la série repart de la prochaine.
            request = movedTo(request, split.plusDays(ChronoUnit.DAYS.between(date, request.date())));
        }
        Set<EventField> changed = changes(toResponse(event, userId, scope == EventScope.ALL ? split : date),
                request, scope != EventScope.ONE);
        Instant now = clock.instant();
        if (scope == EventScope.ONE) {
            event.skip(split, now);
            Event single = newEvent(me, familyId, seriesFor(event, request), withoutRepeat(request));
            log(me, single.getSeriesId(), EventChangeAction.UPDATED, scope, split, changed);
            return toResponse(single, userId, localDate(single));
        }
        LocalDate occurrence = scope == EventScope.FOLLOWING ? split : null;
        if (occurrences(event, filters, localDate(event), split.minusDays(1)).isEmpty()) {
            // Aucune séance avant : toute la série change.
            event.clearSkippedDates();
            apply(event, me, familyId, request);
            repository.flush();
            log(me, event.getSeriesId(), EventChangeAction.UPDATED, scope, occurrence, changed);
            return toResponse(event, userId, localDate(event));
        }
        if (request.date().isBefore(today())) {
            throw ApiException.conflict(PAST);
        }
        event.endRepeatOn(split.minusDays(1), now);
        Event next = newEvent(me, familyId, seriesFor(event, request), request);
        log(me, next.getSeriesId(), EventChangeAction.UPDATED, scope, occurrence, changed);
        return toResponse(next, userId, localDate(next));
    }

    /** Supprime un événement ; pour une série, les séances que {@code scope} désigne à partir de {@code date}. */
    @Transactional
    public void delete(UUID familyId, UUID userId, UUID eventId, EventScope scope, LocalDate date) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Event event = requireVisible(familyId, userId, eventId);
        if (!event.isRepeating()) {
            repository.delete(event);
            log(me, event.getSeriesId(), EventChangeAction.DELETED, null, null, Set.of());
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
        log(me, event.getSeriesId(), EventChangeAction.DELETED, scope, scope == EventScope.ALL ? null : split,
                Set.of());
    }

    /**
     * La même modification, la série commençant le {@code date}. Personnalisé par semaine avec un horaire
     * par jour : l'heure de la requête est celle du jour qu'elle quitte ; le nouveau jour prend la sienne.
     */
    private static EventRequest movedTo(EventRequest r, LocalDate date) {
        LocalTime start = r.startTime();
        LocalTime end = r.endTime();
        RepeatRequest repeat = r.repeat();
        DayOfWeek from = r.date().getDayOfWeek();
        if (!r.allDay() && repeat != null && repeat.days() != null && date.getDayOfWeek() != from) {
            RepeatDay target = repeat.days().stream()
                    .filter(d -> d.dayOfWeek() == date.getDayOfWeek() && d.startTime() != null)
                    .findFirst().orElse(null);
            if (target != null) {
                start = target.startTime();
                end = target.endTime();
                List<RepeatDay> days = repeat.days().stream()
                        .map(d -> d.dayOfWeek() == from ? new RepeatDay(from, r.startTime(), r.endTime()) : d)
                        .toList();
                repeat = new RepeatRequest(repeat.frequency(), repeat.interval(), repeat.unit(),
                        repeat.monthMode(), days, repeat.onlyWith(), repeat.skipHolidays(), repeat.end(),
                        repeat.count());
            }
        }
        return new EventRequest(r.title(), r.type(), r.childIds(), date, r.allDay(), start, end, r.location(),
                r.reminder(), r.visibility(), repeat);
    }

    /**
     * La série à laquelle rattacher ce qui est détaché d'un événement : la même, sauf si la visibilité change
     * (l'historique d'un événement privé ne doit pas apparaître dans celui d'un partagé, ni l'inverse).
     */
    private static UUID seriesFor(Event origin, EventRequest request) {
        return origin.getVisibility() == request.visibility() ? origin.getSeriesId() : null;
    }

    private void log(FamilyMember me, UUID seriesId, EventChangeAction action, EventScope scope,
                     LocalDate occurrence, Set<EventField> fields) {
        if (action == EventChangeAction.UPDATED && fields.isEmpty()) {
            return;
        }
        List<EventField> ordered = fields.stream().sorted().toList();
        changeRepository.save(new EventChange(me.getFamily(), seriesId, me.getUser(), action, scope, occurrence,
                ordered, clock.instant()));
    }

    /** Ce que {@code request} change par rapport à la séance {@code before}. */
    private static Set<EventField> changes(EventResponse before, EventRequest request, boolean withRepeat) {
        Set<EventField> fields = EnumSet.noneOf(EventField.class);
        if (!before.title().equals(request.title().strip())) {
            fields.add(EventField.TITLE);
        }
        if (before.type() != request.type()) {
            fields.add(EventField.TYPE);
        }
        Set<UUID> childIds = before.children().stream().map(EventChildResponse::id).collect(Collectors.toSet());
        if (!childIds.equals(new HashSet<>(request.childIds()))) {
            fields.add(EventField.CHILDREN);
        }
        if (!before.date().equals(request.date())) {
            fields.add(EventField.DATE);
        }
        if (before.allDay() != request.allDay() || (!request.allDay()
                && (!Objects.equals(before.startTime(), request.startTime())
                || !Objects.equals(before.endTime(), request.endTime())))) {
            fields.add(EventField.TIME);
        }
        if (!Objects.equals(before.location(), normalizedLocation(request.location()))) {
            fields.add(EventField.LOCATION);
        }
        if (before.reminder() != request.reminder()) {
            fields.add(EventField.REMINDER);
        }
        if (before.visibility() != request.visibility()) {
            fields.add(EventField.VISIBILITY);
        }
        if (withRepeat && !sameRepeat(before.repeat(), request)) {
            fields.add(EventField.REPEAT);
        }
        return fields;
    }

    private static boolean sameRepeat(RepeatResponse before, EventRequest request) {
        RepeatRequest after = request.repeat();
        if (before == null || after == null) {
            return before == null && after == null;
        }
        if (before.frequency() != after.frequency() || before.onlyWith() != after.onlyWith()
                || before.skipHolidays() != after.skipHolidays() || before.end() != after.end()
                || !Objects.equals(before.count(), after.count())) {
            return false;
        }
        if (after.frequency() != RepeatFrequency.CUSTOM) {
            return true;
        }
        if (!Objects.equals(before.interval(), after.interval()) || before.unit() != after.unit()) {
            return false;
        }
        return switch (after.unit()) {
            case DAY -> true;
            case MONTH -> before.monthMode() == after.monthMode();
            case WEEK -> effectiveTimes(before.days(), null, null, null)
                    .equals(effectiveTimes(after.days(), request.date().getDayOfWeek(),
                            request.allDay() ? null : request.startTime(), request.allDay() ? null : request.endTime()));
        };
    }

    /** Jours choisis et leur horaire effectif (le jour de l'événement prend {@code start}/{@code end}). */
    private static Map<DayOfWeek, List<LocalTime>> effectiveTimes(List<RepeatDay> days, DayOfWeek eventDay,
                                                                  LocalTime start, LocalTime end) {
        Map<DayOfWeek, List<LocalTime>> result = new TreeMap<>();
        boolean own = days != null && days.stream().anyMatch(d -> d.startTime() != null);
        if (days != null) {
            for (RepeatDay day : days) {
                List<LocalTime> times = !own ? List.of()
                        : day.dayOfWeek() == eventDay ? Arrays.asList(start, end)
                        : Arrays.asList(day.startTime(), day.endTime());
                result.put(day.dayOfWeek(), times);
            }
        }
        return result;
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
        return EventOccurrences.between(localDate(series), series.getRepeat(), dayOfWeeks(series),
                series.getSkippedDates(), filters.of(series.getRepeat()), from, to);
    }

    private static Set<DayOfWeek> dayOfWeeks(Event series) {
        return series.getRepeatDays().stream().map(EventRepeatDay::day)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    private Event newEvent(FamilyMember me, UUID familyId, UUID seriesId, EventRequest request) {
        Event event = new Event(me.getFamily(), me.getUser(), seriesId);
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

    private static String normalizedLocation(String location) {
        return location == null || location.isBlank() ? null : location.strip();
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
        Rule rule = toRule(me, request.date(), request, request.repeat());
        Instant now = clock.instant();
        event.update(request.title().strip(), request.type(), children, request.allDay(), startsAt, endsAt,
                normalizedLocation(request.location()), request.reminder(), request.visibility(), now);
        event.repeat(rule == null ? null : rule.repeat(), rule == null ? List.of() : rule.days(), now);
    }

    /** Une règle validée et les jours d'une répétition personnalisée par semaine. */
    private record Rule(EventRepeat repeat, List<EventRepeatDay> days) {
        Set<DayOfWeek> dayOfWeeks() {
            return days.stream().map(EventRepeatDay::day)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        }
    }

    /**
     * Valide la règle. {@code event} : l'événement enregistré, pour vérifier les horaires par jour (null pour
     * l'aperçu, qui ne dépend que des dates).
     */
    private Rule toRule(FamilyMember me, LocalDate first, EventRequest event, RepeatRequest request) {
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
        Integer interval = null;
        RepeatUnit unit = null;
        RepeatMonthMode monthMode = null;
        List<EventRepeatDay> days = List.of();
        if (request.frequency() == RepeatFrequency.CUSTOM) {
            if (request.interval() == null || request.unit() == null) {
                throw ApiException.badRequest("Indiquez « tous les » et l'unité de la répétition.");
            }
            interval = request.interval();
            unit = request.unit();
            if (unit == RepeatUnit.MONTH) {
                if (request.monthMode() == null) {
                    throw ApiException.badRequest("Indiquez le jour du mois.");
                }
                monthMode = request.monthMode();
            } else if (unit == RepeatUnit.WEEK) {
                days = toDays(first, event, request.days());
            }
        }
        LocalDate until = request.end() == RepeatEnd.SCHOOL_YEAR ? schoolYearEnd(me, first) : null;
        return new Rule(new EventRepeat(request.frequency(), interval, unit, monthMode, request.onlyWith(),
                request.skipHolidays(), request.end(), request.count(), until), days);
    }

    /**
     * Jours d'une répétition personnalisée par semaine. Le jour de l'événement en fait partie et garde l'horaire
     * de l'événement ; les autres ont chacun le leur, ou aucun (même horaire chaque jour).
     */
    private static List<EventRepeatDay> toDays(LocalDate first, EventRequest event, List<RepeatDay> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("Choisissez au moins un jour.");
        }
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (RepeatDay day : requested) {
            if (!seen.add(day.dayOfWeek())) {
                throw ApiException.badRequest("Chaque jour n'est choisi qu'une fois.");
            }
        }
        if (!seen.contains(first.getDayOfWeek())) {
            throw ApiException.badRequest("Le jour de l'événement fait partie des jours choisis.");
        }
        List<RepeatDay> others = requested.stream().filter(d -> d.dayOfWeek() != first.getDayOfWeek()).toList();
        boolean own = others.stream().anyMatch(d -> d.startTime() != null || d.endTime() != null);
        if (own) {
            if (event != null && event.allDay()) {
                throw ApiException.badRequest("Les horaires par jour ne concernent pas une journée entière.");
            }
            for (RepeatDay day : others) {
                if (day.startTime() == null || day.endTime() == null) {
                    throw ApiException.badRequest("Indiquez l'horaire de chaque jour.");
                }
                if (!day.endTime().isAfter(day.startTime())) {
                    throw ApiException.badRequest("Pour chaque jour, l'heure de fin suit l'heure de début.");
                }
            }
        }
        return requested.stream()
                .map(d -> d.dayOfWeek() == first.getDayOfWeek() || !own
                        ? new EventRepeatDay(d.dayOfWeek(), null, null)
                        : new EventRepeatDay(d.dayOfWeek(), d.startTime(), d.endTime()))
                .toList();
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

    /** L'événement, ou sa séance du {@code date} (avec l'horaire propre à son jour, s'il en a un). */
    private static EventResponse toResponse(Event event, UUID userId, LocalDate date) {
        LocalTime start = event.getStartsAt().atZone(PARIS).toLocalTime();
        LocalTime end = event.getEndsAt().atZone(PARIS).toLocalTime();
        EventRepeat repeat = event.isRepeating() ? event.getRepeat() : null;
        List<RepeatDay> days = repeat == null ? List.of() : effectiveDays(event, start, end);
        for (RepeatDay day : days) {
            if (day.dayOfWeek() == date.getDayOfWeek() && day.startTime() != null) {
                start = day.startTime();
                end = day.endTime();
            }
        }
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
                date,
                event.isAllDay() ? null : start,
                event.isAllDay() ? null : end,
                event.getLocation(),
                event.getReminder(),
                event.getVisibility(),
                event.getAuthor() == null ? null : event.getAuthor().getFirstName(),
                mine,
                repeat == null ? null : new RepeatResponse(repeat.getFrequency(), repeat.getInterval(),
                        repeat.getUnit(), repeat.getMonthMode(), days, repeat.getOnlyWith(), repeat.skipsHolidays(),
                        repeat.getEnd(), repeat.getCount(), localDate(event), repeat.getUntil()),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }

    /**
     * Jours d'une série personnalisée par semaine, du lundi au dimanche, avec l'horaire effectif de chacun quand
     * les horaires diffèrent (le jour de l'événement prend le sien) ; sans horaire propre, tous null.
     */
    private static List<RepeatDay> effectiveDays(Event event, LocalTime start, LocalTime end) {
        boolean own = !event.isAllDay() && event.getRepeatDays().stream().anyMatch(EventRepeatDay::hasOwnTime);
        DayOfWeek eventDay = localDate(event).getDayOfWeek();
        return event.getRepeatDays().stream()
                .sorted(Comparator.comparing(EventRepeatDay::day))
                .map(d -> !own ? new RepeatDay(d.day(), null, null)
                        : d.hasOwnTime() ? new RepeatDay(d.day(), d.getStartTime(), d.getEndTime())
                        : d.day() == eventDay ? new RepeatDay(d.day(), start, end)
                        : new RepeatDay(d.day(), null, null))
                .toList();
    }
}
