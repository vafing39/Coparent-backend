package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.family.entity.VacationZone;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Dates officielles des vacances scolaires (zones A, B et C), lues dans {@code school-holidays.json} au
 * démarrage. Le fichier vient de data.education.gouv.fr et doit être complété chaque année. La zone OTHER
 * (Corse, outre-mer, étranger) n'a pas de dates : seul le rythme habituel s'applique.
 */
@Component
public class SchoolHolidays {

    static final String RESOURCE = "/school-holidays.json";

    public enum Kind { SHORT, SUMMER }

    /**
     * {@code start} : premier jour sans classe (le samedi) ; {@code resume} : jour de la reprise. Les nuits
     * de vacances vont du soir de la sortie des classes (la veille de {@code start}) à la veille de la reprise.
     */
    public record Holiday(String name, Kind kind, LocalDate start, LocalDate resume) {

        public LocalDate firstNight() {
            return start.minusDays(1);
        }

        public LocalDate lastNight() {
            return resume.minusDays(1);
        }

        public boolean contains(LocalDate night) {
            return !night.isBefore(firstNight()) && !night.isAfter(lastNight());
        }
    }

    private record HolidaysFile(Map<VacationZone, List<Holiday>> zones) {
    }

    private final Map<VacationZone, List<Holiday>> zones;

    public SchoolHolidays() {
        try (InputStream in = SchoolHolidays.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            zones = JsonMapper.builder().build().readValue(in, HolidaysFile.class).zones();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Holiday> forZone(VacationZone zone) {
        return zone == null ? List.of() : zones.getOrDefault(zone, List.of());
    }
}
