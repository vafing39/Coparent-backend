package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.family.dto.CustodyNightResponse;
import com.vynk.coparent_backend.family.dto.CustodyPeriod;
import com.vynk.coparent_backend.family.dto.HandoverResponse;
import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.SummerSplit;
import com.vynk.coparent_backend.family.service.SchoolHolidays.Holiday;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calcul de la garde, sans base de données. Une « nuit » est celle qui commence à la date donnée ; un
 * passage a lieu à l'heure de bascule le jour où le parent de la nuit change.
 * <ul>
 *   <li>Rythme habituel : une séquence de nuits à partir de {@code startDate} (la première bascule), répétée
 *   avant et après. m = parent de départ, o = l'autre (mêmes séquences que l'aperçu de l'app) ; CUSTOM :
 *   lettres A et B.</li>
 *   <li>Petites vacances : partagées par moitié (la 1re a une nuit de plus si le nombre est impair). Les
 *   années paires, {@code holidayFirstHalfEvenYears} a la 1re moitié ; l'ordre s'inverse les années
 *   impaires. L'année est celle du début des vacances.</li>
 *   <li>Été : MONTHS, juillet (et les nuits de fin juin) puis août (et septembre) ; FORTNIGHTS, les
 *   quinzaines 1-15 et 16-fin de juillet puis d'août, en alternance. Le premier parent suit la même règle
 *   d'année paire ou impaire.</li>
 * </ul>
 * Sans règle de vacances enregistrée, le rythme habituel continue pendant les vacances.
 */
public class CustodyCalculator {

    static final Map<CustodyPattern, String> SEQUENCES = Map.of(
            CustodyPattern.ALTERNATE_WEEKS, "mmmmmmmooooooo",
            CustodyPattern.TWO_TWO_THREE, "mmoommmoommooo",
            CustodyPattern.TWO_TWO_FIVE_FIVE, "mmoommmmmooooo",
            CustodyPattern.ALTERNATE_WEEKENDS_WEDNESDAY, "mmomooommommmm");

    /** Au-delà, on considère que le rythme ne prévoit aucun passage. */
    static final int NEXT_HANDOVER_SEARCH_DAYS = 400;

    private record Night(FamilyRole parent, CustodyPeriod period, String holiday) {
    }

    private final CustodySchedule schedule;
    private final List<Holiday> holidays;

    public CustodyCalculator(CustodySchedule schedule, List<Holiday> holidays) {
        this.schedule = schedule;
        this.holidays = holidays;
    }

    /** Les nuits de {@code from} à {@code to} inclus. */
    public List<CustodyNightResponse> nights(LocalDate from, LocalDate to) {
        List<CustodyNightResponse> nights = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Night night = night(date);
            nights.add(new CustodyNightResponse(date, night.parent(), night.period(), night.holiday()));
        }
        return nights;
    }

    /** Les passages qui ont lieu de {@code from} à {@code to} inclus. */
    public List<HandoverResponse> handovers(LocalDate from, LocalDate to) {
        List<HandoverResponse> handovers = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            handoverOn(date).ifPresent(handovers::add);
        }
        return handovers;
    }

    /** Le premier passage strictement après {@code now} (heure de Paris). */
    public Optional<HandoverResponse> nextHandover(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        for (int i = 0; i <= NEXT_HANDOVER_SEARCH_DAYS; i++, date = date.plusDays(1)) {
            Optional<HandoverResponse> handover = handoverOn(date);
            if (handover.isPresent() && handover.get().at().isAfter(now)) {
                return handover;
            }
        }
        return Optional.empty();
    }

    public FamilyRole parentOn(LocalDate night) {
        return night(night).parent();
    }

    private Optional<HandoverResponse> handoverOn(LocalDate date) {
        FamilyRole before = parentOn(date.minusDays(1));
        FamilyRole after = parentOn(date);
        if (before == after) {
            return Optional.empty();
        }
        LocalTime time = schedule.getHandoverTime();
        return Optional.of(new HandoverResponse(date.atTime(time), before, after));
    }

    private Night night(LocalDate date) {
        for (Holiday holiday : holidays) {
            if (holiday.contains(date)) {
                FamilyRole parent = holidayParent(holiday, date);
                if (parent != null) {
                    CustodyPeriod period = holiday.kind() == SchoolHolidays.Kind.SUMMER
                            ? CustodyPeriod.SUMMER : CustodyPeriod.SHORT_HOLIDAY;
                    return new Night(parent, period, holiday.name());
                }
                return new Night(regularParent(date), CustodyPeriod.REGULAR, holiday.name());
            }
        }
        return new Night(regularParent(date), CustodyPeriod.REGULAR, null);
    }

    /** Null : pas de règle enregistrée pour ces vacances, le rythme habituel s'applique. */
    private FamilyRole holidayParent(Holiday holiday, LocalDate date) {
        FamilyRole evenYearsFirst = schedule.getHolidayFirstHalfEvenYears();
        if (evenYearsFirst == null) {
            return null;
        }
        FamilyRole first = holiday.start().getYear() % 2 == 0 ? evenYearsFirst : other(evenYearsFirst);

        if (holiday.kind() == SchoolHolidays.Kind.SHORT) {
            long count = ChronoUnit.DAYS.between(holiday.firstNight(), holiday.lastNight()) + 1;
            long index = ChronoUnit.DAYS.between(holiday.firstNight(), date);
            return index < (count + 1) / 2 ? first : other(first);
        }

        SummerSplit split = schedule.getSummerSplit();
        if (split == null) {
            return null;
        }
        int block = split == SummerSplit.MONTHS ? summerMonth(date) : summerFortnight(date);
        return block % 2 == 0 ? first : other(first);
    }

    /** 0 : juillet (et avant), 1 : août (et après). */
    private static int summerMonth(LocalDate date) {
        return date.getMonthValue() <= Month.JULY.getValue() ? 0 : 1;
    }

    /** 0 à 3 : 1-15 juillet (et avant), 16-31 juillet, 1-15 août, 16-31 août (et après). */
    private static int summerFortnight(LocalDate date) {
        int month = date.getMonthValue();
        if (month < Month.JULY.getValue()) {
            return 0;
        }
        if (month > Month.AUGUST.getValue()) {
            return 3;
        }
        int secondHalf = date.getDayOfMonth() > 15 ? 1 : 0;
        return (month == Month.JULY.getValue() ? 0 : 2) + secondHalf;
    }

    private FamilyRole regularParent(LocalDate date) {
        String sequence = schedule.getPattern() == CustodyPattern.CUSTOM
                ? schedule.getCustomSequence()
                : SEQUENCES.get(schedule.getPattern());
        int index = Math.floorMod(ChronoUnit.DAYS.between(schedule.getStartDate(), date), sequence.length());
        char letter = sequence.charAt(index);
        return switch (letter) {
            case 'A' -> FamilyRole.PARENT_A;
            case 'B' -> FamilyRole.PARENT_B;
            case 'm' -> schedule.getStartingParent();
            default -> other(schedule.getStartingParent());
        };
    }

    private static FamilyRole other(FamilyRole role) {
        return role == FamilyRole.PARENT_A ? FamilyRole.PARENT_B : FamilyRole.PARENT_A;
    }
}
