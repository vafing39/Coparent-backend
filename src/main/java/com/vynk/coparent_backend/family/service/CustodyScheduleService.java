package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.CustodyScheduleRequest;
import com.vynk.coparent_backend.family.dto.CustodyScheduleResponse;
import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import com.vynk.coparent_backend.family.mapper.FamilyMapper;
import com.vynk.coparent_backend.family.repository.CustodyScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Étape 4 : proposition du rythme. Elle reste PROPOSED jusqu'à la validation par l'autre parent
 * (fonction « garde », à venir) ; en mode solo, c'est elle qui s'applique en attendant.
 */
@Service
@RequiredArgsConstructor
public class CustodyScheduleService {

    private final CustodyScheduleRepository scheduleRepository;
    private final FamilyAccess familyAccess;
    private final FamilyMapper mapper;

    /** Replaces the user's own pending proposal, so going back and forth in onboarding adds no rows. */
    @Transactional
    public CustodyScheduleResponse propose(UUID familyId, UUID userId, CustodyScheduleRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        boolean custom = request.pattern() == CustodyPattern.CUSTOM;
        if (custom != (request.customSequence() != null)) {
            throw ApiException.badRequest(custom
                    ? "Indiquez la séquence des nuits pour un rythme personnalisé."
                    : "La séquence des nuits ne concerne que le rythme personnalisé.");
        }

        CustodySchedule schedule = scheduleRepository
                .findFirstByFamilyIdAndStatusAndProposedByIdOrderByCreatedAtDesc(
                        familyId, ScheduleStatus.PROPOSED, userId)
                .orElseGet(() -> new CustodySchedule(me.getFamily(), me.getUser()));
        schedule.define(request.pattern(), request.customSequence(), request.startDate(), request.handoverTime(),
                request.startingParent(), request.holidayFirstHalfEvenYears(), request.summerSplit());

        return mapper.toResponse(scheduleRepository.save(schedule));
    }
}
