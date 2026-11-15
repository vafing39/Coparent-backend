package com.vynk.coparent_backend.feed.service;

import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.service.RelativeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Accès au fil : un parent de la famille, ou un proche actif. Tout autre compte reçoit 404, comme pour une famille
 * inconnue. Seul le fil passe par ici : les autres routes de la famille gardent {@code FamilyAccess.requireMember}.
 */
@Component
@RequiredArgsConstructor
public class FeedAccess {

    private final FamilyMemberRepository memberRepository;
    private final RelativeService relativeService;

    @Transactional(readOnly = true)
    public FeedViewer require(UUID familyId, UUID userId) {
        return memberRepository.findByFamilyIdAndUserId(familyId, userId)
                .map(m -> new FeedViewer(m.getFamily(), m.getUser(), m.getRole(), null))
                .orElseGet(() -> {
                    var relative = relativeService.requireActiveRelative(familyId, userId);
                    return new FeedViewer(relative.getFamily(), relative.getUser(), null, relative);
                });
    }
}
