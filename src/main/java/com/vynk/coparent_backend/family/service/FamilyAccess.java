package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Access check run by every family endpoint. A non-member gets 404, exactly like a family that does not
 * exist, so the API never reveals other families.
 */
@Component
@RequiredArgsConstructor
public class FamilyAccess {

    static final String FAMILY_NOT_FOUND = "Famille introuvable.";

    private final FamilyMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public FamilyMember requireMember(UUID familyId, UUID userId) {
        return memberRepository.findByFamilyIdAndUserId(familyId, userId)
                .orElseThrow(() -> ApiException.notFound(FAMILY_NOT_FOUND));
    }
}
