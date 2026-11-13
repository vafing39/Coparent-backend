package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.CustodyScheduleRequest;
import com.vynk.coparent_backend.family.dto.CustodyScheduleResponse;
import com.vynk.coparent_backend.family.entity.CustodyPattern;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import com.vynk.coparent_backend.family.mapper.FamilyMapper;
import com.vynk.coparent_backend.family.repository.CustodyScheduleRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.entity.ToDoKind;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Rythme de garde (spec §3). Une proposition reste PROPOSED jusqu'à sa validation par l'autre parent, qui la rend
 * ACTIVE ; il peut aussi proposer une modification, qui remplace la proposition reçue. En solo, la proposition
 * s'applique en attendant. Chaque proposition à valider est une demande « À traiter » (CUSTODY_SCHEDULE).
 */
@Service
@RequiredArgsConstructor
public class CustodyScheduleService {

    private final CustodyScheduleRepository scheduleRepository;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final ToDoItemRepository toDoRepository;
    private final FamilyAccess familyAccess;
    private final FamilyMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustodyScheduleResponse get(UUID familyId, UUID scheduleId, UUID userId) {
        familyAccess.requireMember(familyId, userId);
        return mapper.toResponse(requireSchedule(familyId, scheduleId));
    }

    /**
     * Replaces the user's own pending proposal, so going back and forth in onboarding adds no rows. With two
     * parents, it also replaces the other parent's pending proposal (counter-proposal) and asks them to validate.
     */
    @Transactional
    public CustodyScheduleResponse propose(UUID familyId, UUID userId, CustodyScheduleRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        boolean custom = request.pattern() == CustodyPattern.CUSTOM;
        if (custom != (request.customSequence() != null)) {
            throw ApiException.badRequest(custom
                    ? "Indiquez la séquence des nuits pour un rythme personnalisé."
                    : "La séquence des nuits ne concerne que le rythme personnalisé.");
        }
        familyRepository.findByIdForUpdate(familyId);
        Instant now = clock.instant();
        Optional<FamilyMember> other = otherParent(familyId, userId);

        other.flatMap(o -> scheduleRepository.findFirstByFamilyIdAndStatusAndProposedByIdOrderByCreatedAtDesc(
                        familyId, ScheduleStatus.PROPOSED, o.getUser().getId()))
                .ifPresent(received -> {
                    received.replace(me.getUser(), now);
                    resolveToDo(familyId, received.getId(), now);
                });

        CustodySchedule schedule = scheduleRepository
                .findFirstByFamilyIdAndStatusAndProposedByIdOrderByCreatedAtDesc(
                        familyId, ScheduleStatus.PROPOSED, userId)
                .orElseGet(() -> new CustodySchedule(me.getFamily(), me.getUser()));
        schedule.define(request.pattern(), request.customSequence(), request.startDate(), request.handoverTime(),
                request.startingParent(), request.holidayFirstHalfEvenYears(), request.summerSplit());
        CustodySchedule saved = scheduleRepository.save(schedule);

        if (other.isPresent()) {
            resolveToDo(familyId, saved.getId(), now); // a re-saved proposal gets a fresh request (subject may change)
            askValidation(me.getFamily(), saved, me.getUser(), other.get().getUser());
        }
        return mapper.toResponse(saved);
    }

    /** The other parent validates the proposal: it applies from now on, the previous rhythm is replaced. */
    @Transactional
    public CustodyScheduleResponse accept(UUID familyId, UUID scheduleId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        familyRepository.findByIdForUpdate(familyId);
        CustodySchedule schedule = requireSchedule(familyId, scheduleId);
        if (otherParent(familyId, userId).isEmpty()) {
            throw ApiException.conflict("Le rythme sera validé par l'autre parent quand il aura rejoint la famille.");
        }
        if (schedule.getStatus() != ScheduleStatus.PROPOSED) {
            throw ApiException.conflict("Ce rythme n'attend plus de validation.");
        }
        if (schedule.getProposedBy() != null && schedule.getProposedBy().getId().equals(userId)) {
            throw ApiException.conflict("Ce rythme doit être validé par l'autre parent.");
        }

        Instant now = clock.instant();
        scheduleRepository.findByFamilyIdAndStatus(familyId, ScheduleStatus.ACTIVE)
                .forEach(active -> active.replace(me.getUser(), now));
        scheduleRepository.flush(); // one ACTIVE per family (unique active_family_id)
        schedule.activate(me.getUser(), now);
        resolveToDo(familyId, scheduleId, now);
        return mapper.toResponse(schedule);
    }

    /** Parent B just joined: they are asked to validate the pending proposal, if any. */
    void askNewParent(Family family, User newParent) {
        scheduleRepository.findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(family.getId(), ScheduleStatus.PROPOSED)
                .ifPresent(schedule -> askValidation(family, schedule, schedule.getProposedBy(), newParent));
    }

    private void askValidation(Family family, CustodySchedule schedule, User requester, User assignee) {
        toDoRepository.save(new ToDoItem(family, ToDoKind.CUSTODY_SCHEDULE, schedule.getId(),
                "Rythme : " + schedule.getPattern().label(), requester, assignee));
    }

    private CustodySchedule requireSchedule(UUID familyId, UUID scheduleId) {
        return scheduleRepository.findByIdAndFamilyId(scheduleId, familyId)
                .orElseThrow(() -> ApiException.notFound("Ce rythme n'existe pas."));
    }

    private Optional<FamilyMember> otherParent(UUID familyId, UUID userId) {
        return memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .filter(m -> !m.getUser().getId().equals(userId))
                .findFirst();
    }

    private void resolveToDo(UUID familyId, UUID scheduleId, Instant now) {
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(
                        familyId, ToDoKind.CUSTODY_SCHEDULE, scheduleId)
                .forEach(item -> item.resolve(now));
    }
}
