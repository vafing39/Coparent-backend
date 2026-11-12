package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.family.dto.CustodyNightResponse;
import com.vynk.coparent_backend.family.dto.CustodyPeriod;
import com.vynk.coparent_backend.family.dto.HandoverResponse;
import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.SummerSplit;
import com.vynk.coparent_backend.family.service.SchoolHolidays.Holiday;
import com.vynk.coparent_backend.family.service.SchoolHolidays.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.vynk.coparent_backend.family.entity.FamilyRole.PARENT_A;
import static com.vynk.coparent_backend.family.entity.FamilyRole.PARENT_B;
import static org.assertj.core.api.Assertions.assertThat;

class CustodyCalculatorTest {

    /** Vendredi : première bascule. */
    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final LocalTime HANDOVER = LocalTime.of(17, 50);

    // Dates officielles, zone A.
    private static final Holiday TOUSSAINT_2025 = new Holiday("Vacances de la Toussaint", Kind.SHORT,
            LocalDate.of(2025, 10, 18), LocalDate.of(2025, 11, 3));
    private static final Holiday NOEL_2026 = new Holiday("Vacances de Noël", Kind.SHORT,
            LocalDate.of(2026, 12, 19), LocalDate.of(2027, 1, 4));
    private static final Holiday ETE_2026 = new Holiday("Vacances d'Été", Kind.SUMMER,
            LocalDate.of(2026, 7, 4), LocalDate.of(2026, 9, 1));
    private static final Holiday ETE_2027 = new Holiday("Vacances d'Été", Kind.SUMMER,
            LocalDate.of(2027, 7, 3), LocalDate.of(2027, 9, 2));

    private static CustodySchedule schedule(CustodyPattern pattern, String customSequence,
                                            FamilyRole holidayFirstHalfEvenYears, SummerSplit summerSplit) {
        CustodySchedule schedule = new CustodySchedule(null, null);
        schedule.define(pattern, customSequence, START, HANDOVER, PARENT_A, holidayFirstHalfEvenYears, summerSplit);
        return schedule;
    }

    private static CustodyCalculator regular(CustodyPattern pattern) {
        return new CustodyCalculator(schedule(pattern, null, null, null), List.of());
    }

    /** Les parents de 14 nuits à partir de START, en lettres (A ou B). */
    private static String fortnight(CustodyCalculator calculator, LocalDate from) {
        return calculator.nights(from, from.plusDays(13)).stream()
                .map(night -> night.parent() == PARENT_A ? "A" : "B")
                .collect(Collectors.joining());
    }

    @Test
    void presetPatternsFollowTheAppPreview() {
        assertThat(fortnight(regular(CustodyPattern.ALTERNATE_WEEKS), START)).isEqualTo("AAAAAAABBBBBBB");
        assertThat(fortnight(regular(CustodyPattern.TWO_TWO_THREE), START)).isEqualTo("AABBAAABBAABBB");
        assertThat(fortnight(regular(CustodyPattern.TWO_TWO_FIVE_FIVE), START)).isEqualTo("AABBAAAAABBBBB");
        assertThat(fortnight(regular(CustodyPattern.ALTERNATE_WEEKENDS_WEDNESDAY), START))
                .isEqualTo("AABABBBAABAAAA");
    }

    @Test
    void startingParentBSwapsTheSequence() {
        CustodySchedule schedule = new CustodySchedule(null, null);
        schedule.define(CustodyPattern.ALTERNATE_WEEKS, null, START, HANDOVER, PARENT_B, null, null);
        assertThat(fortnight(new CustodyCalculator(schedule, List.of()), START)).isEqualTo("BBBBBBBAAAAAAA");
    }

    @Test
    void rhythmRepeatsBeforeAndAfterTheStartDate() {
        CustodyCalculator calculator = regular(CustodyPattern.ALTERNATE_WEEKS);
        assertThat(calculator.parentOn(START.minusDays(1))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(START.minusDays(14))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(START.plusDays(28))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(START.plusDays(35))).isEqualTo(PARENT_B);
    }

    @Test
    void customSequenceRepeatsWithItsOwnLength() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.CUSTOM, "AAB", null, null), List.of());
        assertThat(fortnight(calculator, START)).isEqualTo("AABAABAABAABAA");
        assertThat(calculator.parentOn(START.minusDays(1))).isEqualTo(PARENT_B);
    }

    @Test
    void handoversHappenAtTheHandoverTimeWhenTheParentChanges() {
        CustodyCalculator calculator = regular(CustodyPattern.ALTERNATE_WEEKS);
        assertThat(calculator.handovers(START.minusDays(1), START.plusDays(13))).containsExactly(
                new HandoverResponse(START.atTime(HANDOVER), PARENT_B, PARENT_A),
                new HandoverResponse(START.plusDays(7).atTime(HANDOVER), PARENT_A, PARENT_B));
    }

    @Test
    void nextHandoverIsStrictlyAfterNow() {
        CustodyCalculator calculator = regular(CustodyPattern.ALTERNATE_WEEKS);
        assertThat(calculator.nextHandover(START.atTime(17, 0)))
                .contains(new HandoverResponse(START.atTime(HANDOVER), PARENT_B, PARENT_A));
        assertThat(calculator.nextHandover(START.atTime(HANDOVER)))
                .contains(new HandoverResponse(START.plusDays(7).atTime(HANDOVER), PARENT_A, PARENT_B));
        assertThat(calculator.nextHandover(LocalDateTime.of(2026, 11, 1, 9, 0)).orElseThrow().at())
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 17, 50));
    }

    @Test
    void noNextHandoverWhenTheChildrenNeverChangeHome() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.CUSTOM, "AAAA", null, null), List.of());
        assertThat(calculator.nextHandover(START.atStartOfDay())).isEmpty();
    }

    @Test
    void shortHolidaysAreSplitInHalvesFirstHalfToTheEvenYearsParent() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.ALTERNATE_WEEKS, null, PARENT_B, SummerSplit.MONTHS), List.of(NOEL_2026));
        // Nuits du vendredi 18 décembre au dimanche 3 janvier : 17 nuits, 9 puis 8.
        List<CustodyNightResponse> nights = calculator.nights(LocalDate.of(2026, 12, 18), LocalDate.of(2027, 1, 3));
        assertThat(nights).hasSize(17).allSatisfy(night -> {
            assertThat(night.period()).isEqualTo(CustodyPeriod.SHORT_HOLIDAY);
            assertThat(night.holiday()).isEqualTo("Vacances de Noël");
        });
        assertThat(nights.subList(0, 9)).allSatisfy(night -> assertThat(night.parent()).isEqualTo(PARENT_B));
        assertThat(nights.subList(9, 17)).allSatisfy(night -> assertThat(night.parent()).isEqualTo(PARENT_A));
        assertThat(calculator.handovers(LocalDate.of(2026, 12, 27), LocalDate.of(2026, 12, 27))).containsExactly(
                new HandoverResponse(LocalDateTime.of(2026, 12, 27, 17, 50), PARENT_B, PARENT_A));

        // Hors vacances : le rythme habituel, sans nom de vacances.
        CustodyNightResponse before = calculator.nights(LocalDate.of(2026, 12, 17), LocalDate.of(2026, 12, 17)).get(0);
        assertThat(before.period()).isEqualTo(CustodyPeriod.REGULAR);
        assertThat(before.holiday()).isNull();
    }

    @Test
    void oddYearsSwapTheHalves() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.ALTERNATE_WEEKS, null, PARENT_B, SummerSplit.MONTHS),
                List.of(TOUSSAINT_2025));
        // 2025 est impaire : PARENT_A a la 1re moitié (nuits du 17 au 25 octobre).
        assertThat(calculator.parentOn(LocalDate.of(2025, 10, 17))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2025, 10, 25))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2025, 10, 26))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2025, 11, 2))).isEqualTo(PARENT_B);
    }

    @Test
    void summerByMonthsSwapsEveryYear() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.ALTERNATE_WEEKS, null, PARENT_A, SummerSplit.MONTHS),
                List.of(ETE_2026, ETE_2027));
        assertThat(calculator.parentOn(LocalDate.of(2026, 7, 3))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2026, 7, 31))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2026, 8, 1))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2026, 8, 31))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2027, 7, 2))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2027, 9, 1))).isEqualTo(PARENT_A);
        assertThat(calculator.nights(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 3)).get(0).period())
                .isEqualTo(CustodyPeriod.SUMMER);
    }

    @Test
    void summerByFortnightsAlternates() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.ALTERNATE_WEEKS, null, PARENT_A, SummerSplit.FORTNIGHTS),
                List.of(ETE_2026, ETE_2027));
        assertThat(calculator.parentOn(LocalDate.of(2026, 7, 3))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2026, 7, 15))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2026, 7, 16))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2026, 8, 1))).isEqualTo(PARENT_A);
        assertThat(calculator.parentOn(LocalDate.of(2026, 8, 16))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2026, 8, 31))).isEqualTo(PARENT_B);
        // 2027 est impaire : PARENT_B commence ; la nuit du 1er septembre compte avec la dernière quinzaine.
        assertThat(calculator.parentOn(LocalDate.of(2027, 7, 2))).isEqualTo(PARENT_B);
        assertThat(calculator.parentOn(LocalDate.of(2027, 9, 1))).isEqualTo(PARENT_A);
    }

    @Test
    void withoutHolidayRulesTheRhythmContinues() {
        CustodyCalculator calculator = new CustodyCalculator(
                schedule(CustodyPattern.ALTERNATE_WEEKS, null, null, null), List.of(NOEL_2026));
        CustodyNightResponse night = calculator.nights(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 20)).get(0);
        assertThat(night.period()).isEqualTo(CustodyPeriod.REGULAR);
        assertThat(night.holiday()).isEqualTo("Vacances de Noël");
        assertThat(night.parent()).isEqualTo(regular(CustodyPattern.ALTERNATE_WEEKS).parentOn(night.date()));
    }
}
