package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.FamilyRequest;
import com.vynk.coparent_backend.family.dto.FamilyResponse;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import com.vynk.coparent_backend.family.mapper.FamilyMapper;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.repository.CustodyScheduleRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Étape 2 : la famille, et sa vue complète pour l'app. */
@Service
@RequiredArgsConstructor
public class FamilyService {

    static final String ALREADY_IN_A_FAMILY = "Vous faites déjà partie d'une famille.";
    static final String EMAIL_NOT_VERIFIED = "Vérifiez votre adresse email.";

    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final ChildRepository childRepository;
    private final CustodyScheduleRepository scheduleRepository;
    private final FamilyAccess familyAccess;
    private final UserService userService;
    private final FamilyMapper mapper;

    @Transactional
    public FamilyResponse create(UUID userId, FamilyRequest request) {
        User user = requireVerifiedUser(userId);
        if (memberRepository.existsByUserId(userId)) {
            throw ApiException.conflict(ALREADY_IN_A_FAMILY);
        }

        Family family = familyRepository.save(new Family(request.name().trim(), request.vacationZone(), user));
        FamilyMember me = memberRepository.save(new FamilyMember(family, user, FamilyRole.PARENT_A));

        return view(family, me);
    }

    /** 404 when the user has no family yet: the app then continues the onboarding. */
    @Transactional(readOnly = true)
    public FamilyResponse getMine(UUID userId) {
        FamilyMember me = memberRepository.findFirstByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Aucune famille pour ce compte."));
        return view(me.getFamily(), me);
    }

    @Transactional
    public FamilyResponse update(UUID familyId, UUID userId, FamilyRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Family family = me.getFamily();
        family.update(request.name().trim(), request.vacationZone());
        return view(family, me);
    }

    User requireVerifiedUser(UUID userId) {
        User user = userService.getActiveUser(userId);
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden(EMAIL_NOT_VERIFIED);
        }
        return user;
    }

    FamilyResponse view(Family family, FamilyMember me) {
        UUID familyId = family.getId();
        return mapper.toResponse(family, me,
                memberRepository.findByFamilyIdOrderByRoleAsc(familyId),
                childRepository.findByFamilyIdOrderByBirthDateAscCreatedAtAsc(familyId),
                currentSchedule(familyId).orElse(null));
    }

    /** The schedule in force, else the latest proposal (in solo mode the proposal applies until parent B joins). */
    Optional<CustodySchedule> currentSchedule(UUID familyId) {
        return scheduleRepository.findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(familyId, ScheduleStatus.ACTIVE)
                .or(() -> scheduleRepository.findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(
                        familyId, ScheduleStatus.PROPOSED));
    }
}
