package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.family.entity.VacationZone;
import com.vynk.coparent_backend.family.service.SchoolHolidays.Holiday;
import com.vynk.coparent_backend.family.service.SchoolHolidays.Kind;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolHolidaysTest {

    private final SchoolHolidays holidays = new SchoolHolidays();

    @Test
    void zonesABAndCHaveFiveHolidaysPerSchoolYear() {
        for (VacationZone zone : new VacationZone[]{VacationZone.A, VacationZone.B, VacationZone.C}) {
            assertThat(holidays.forZone(zone)).hasSize(10);
            assertThat(holidays.forZone(zone).stream().filter(h -> h.kind() == Kind.SUMMER)).hasSize(2);
            // Les vacances commencent un samedi ; la veille au soir est la première nuit.
            assertThat(holidays.forZone(zone)).allSatisfy(h -> {
                assertThat(h.start().getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
                assertThat(h.resume()).isAfter(h.start());
            });
        }
    }

    @Test
    void readsOfficialDates() {
        assertThat(holidays.forZone(VacationZone.A)).contains(new Holiday("Vacances de Noël", Kind.SHORT,
                LocalDate.of(2026, 12, 19), LocalDate.of(2027, 1, 4)));
        assertThat(holidays.forZone(VacationZone.C)).contains(new Holiday("Vacances d'Hiver", Kind.SHORT,
                LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 22)));
    }

    @Test
    void zoneOtherHasNoDates() {
        assertThat(holidays.forZone(VacationZone.OTHER)).isEmpty();
        assertThat(holidays.forZone(null)).isEmpty();
    }
}
