package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.auth.service.TokenService;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.family.dto.FamilyResponse;
import com.vynk.coparent_backend.family.dto.InvitationPreviewResponse;
import com.vynk.coparent_backend.family.dto.InvitationRequest;
import com.vynk.coparent_backend.family.dto.InvitationResponse;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyInvitation;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.mapper.FamilyMapper;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.repository.FamilyInvitationRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.UUID;

/** Étape 5 : invitation de l'autre parent, aperçu et acceptation (spec §3). */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String INVALID_INVITATION = "Cette invitation n'est plus valide.";
    private static final String FAMILY_FULL = "La famille compte déjà deux parents.";

    private final FamilyInvitationRepository invitationRepository;
    private final FamilyMemberRepository memberRepository;
    private final ChildRepository childRepository;
    private final FamilyAccess familyAccess;
    private final FamilyService familyService;
    private final TokenService tokenService;
    private final AuthProperties properties;
    private final FamilyMapper mapper;
    private final Clock clock;

    /** A new link revokes the previous unused ones (renewal). */
    @Transactional
    public InvitationResponse create(UUID familyId, UUID userId, InvitationRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        if (memberRepository.countByFamilyId(familyId) >= 2) {
            throw ApiException.conflict(FAMILY_FULL);
        }

        Instant now = clock.instant();
        invitationRepository.revokePending(familyId, now);
        String rawToken = tokenService.generateOpaqueToken();
        Instant expiresAt = now.plus(properties.invitationTtl());
        invitationRepository.save(new FamilyInvitation(me.getFamily(), me.getUser(),
                request.inviteeFirstName().trim(), tokenService.hash(rawToken), expiresAt));

        return new InvitationResponse(properties.publicBaseUrl() + "/invitation?token=" + rawToken, expiresAt);
    }

    /** Public: only what the spec allows before acceptance. */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String rawToken) {
        FamilyInvitation invitation = invitationRepository.findByTokenHash(tokenService.hash(rawToken))
                .filter(i -> i.isUsable(clock.instant()))
                .orElseThrow(() -> ApiException.notFound(INVALID_INVITATION));
        UUID familyId = invitation.getFamily().getId();
        LocalDate today = LocalDate.now(clock.withZone(PARIS));

        return new InvitationPreviewResponse(
                invitation.getInvitedBy().getFirstName(),
                childRepository.findByFamilyIdOrderByBirthDateAscCreatedAtAsc(familyId).stream()
                        .map(c -> new InvitationPreviewResponse.ChildPreview(
                                c.getFirstName(), Period.between(c.getBirthDate(), today).getYears()))
                        .toList(),
                familyService.currentSchedule(familyId).map(mapper::toResponse).orElse(null),
                invitation.getExpiresAt());
    }

    /** The invitee becomes parent B. The rhythm stays PROPOSED until they validate it (custody feature). */
    @Transactional
    public FamilyResponse accept(String rawToken, UUID userId) {
        Instant now = clock.instant();
        FamilyInvitation invitation = invitationRepository.findByTokenHashForUpdate(tokenService.hash(rawToken))
                .filter(i -> i.isUsable(now))
                .orElseThrow(() -> ApiException.notFound(INVALID_INVITATION));
        User user = familyService.requireVerifiedUser(userId);
        Family family = invitation.getFamily();

        if (invitation.getInvitedBy().getId().equals(userId)) {
            throw ApiException.conflict("Cette invitation est destinée à l'autre parent.");
        }
        if (memberRepository.existsByUserId(userId)) {
            throw ApiException.conflict(FamilyService.ALREADY_IN_A_FAMILY);
        }
        if (memberRepository.countByFamilyId(family.getId()) >= 2) {
            throw ApiException.conflict(FAMILY_FULL);
        }

        FamilyMember member;
        try {
            member = memberRepository.saveAndFlush(new FamilyMember(family, user, FamilyRole.PARENT_B));
        } catch (DataIntegrityViolationException e) { // parent B joined at the same time
            throw ApiException.conflict(FAMILY_FULL);
        }
        invitation.accept(user, now);

        return familyService.view(family, member);
    }
}
