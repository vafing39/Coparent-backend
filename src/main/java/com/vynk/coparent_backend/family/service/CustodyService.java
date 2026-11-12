package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.CustodyResponse;
import com.vynk.coparent_backend.family.dto.HandoverResponse;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import com.vynk.coparent_backend.family.repository.CustodyScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Garde calculée pour l'app (accueil, calendrier). S'appuie sur le rythme ACTIVE, sinon sur la dernière
 * proposition (mode solo ou validation en attente). Le calcul lui-même est dans {@link CustodyCalculator}.
 */
@Service
@RequiredArgsConstructor
public class CustodyService {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int MAX_DAYS = 92;
    static final String NO_SCHEDULE = "Aucun rythme de garde n'est encore défini.";

    private final FamilyAccess familyAccess;
    private final CustodyScheduleRepository scheduleRepository;
    private final SchoolHolidays schoolHolidays;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustodyResponse compute(UUID familyId, UUID userId, LocalDate from, LocalDate to) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.badRequest(
                    "Période invalide : la date de fin suit la date de début, " + MAX_DAYS + " jours au plus.");
        }
        CustodySchedule schedule = currentSchedule(familyId).orElseThrow(() -> ApiException.notFound(NO_SCHEDULE));
        CustodyCalculator calculator = calculator(me, schedule);
        return new CustodyResponse(
                schedule.getStatus(),
                calculator.nights(from, to),
                calculator.handovers(from, to),
                calculator.nextHandover(now()).orElse(null));
    }

    /** Premier passage après maintenant (heure de Paris), ou vide sans rythme ni passage prévu. L'accès est vérifié par l'appelant. */
    @Transactional(readOnly = true)
    public Optional<HandoverResponse> nextHandover(FamilyMember me) {
        UUID familyId = me.getFamily().getId();
        return currentSchedule(familyId).flatMap(schedule -> calculator(me, schedule).nextHandover(now()));
    }

    /** Le rythme ACTIVE, sinon la dernière proposition. */
    private Optional<CustodySchedule> currentSchedule(UUID familyId) {
        return scheduleRepository
                .findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(familyId, ScheduleStatus.ACTIVE)
                .or(() -> scheduleRepository
                        .findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(familyId, ScheduleStatus.PROPOSED));
    }

    private CustodyCalculator calculator(FamilyMember me, CustodySchedule schedule) {
        return new CustodyCalculator(schedule, schoolHolidays.forZone(me.getFamily().getVacationZone()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), PARIS);
    }
}
