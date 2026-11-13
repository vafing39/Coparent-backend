package com.vynk.coparent_backend.account.service;

import com.vynk.coparent_backend.auth.repository.OneTimeTokenRepository;
import com.vynk.coparent_backend.auth.repository.RefreshTokenRepository;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.entity.UserStatus;
import com.vynk.coparent_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Deletes the personal data of deleted accounts once their 30 days are over (spec §1). The user row stays,
 * anonymised, so messages (whose hash chain uses the sender's id), events and history keep their author for the
 * other parent; its name becomes {@link #DELETED_NAME}. A family left without any active parent is deleted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountPurgeJob {

    /** First name shown to the other parent once the account is purged. */
    public static final String DELETED_NAME = "Compte supprimé";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final FamilyMemberRepository memberRepository;
    private final FamilyRepository familyRepository;
    private final Clock clock;

    /** Every night at 3 a.m. (Paris). Returns the number of accounts purged. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Paris")
    @Transactional
    public int purgeDueAccounts() {
        List<User> due = userRepository.findByStatusAndDeletionDueAtLessThanEqual(UserStatus.DEACTIVATED,
                clock.instant());
        due.forEach(this::purge);
        if (!due.isEmpty()) {
            log.info("[account] Purged {} deleted account(s)", due.size());
        }
        return due.size();
    }

    private void purge(User user) {
        UUID id = user.getId();
        memberRepository.findFirstByUserId(id).ifPresent(this::deleteFamilyIfNoActiveParent);
        userRepository.deleteIdentities(id);
        refreshTokenRepository.deleteAllForUser(id);
        oneTimeTokenRepository.deleteAllForUser(id);
        user.setEmail("deleted-" + id + "@deleted.invalid");
        user.setFirstName(DELETED_NAME);
        user.setPasswordHash(null);
        user.setEmailVerifiedAt(null);
        // Marks the purge as done: the account is no longer picked up.
        user.setDeletionDueAt(null);
    }

    private void deleteFamilyIfNoActiveParent(FamilyMember membership) {
        UUID familyId = membership.getFamily().getId();
        boolean someoneLeft = memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .anyMatch(member -> member.getUser().isActive());
        if (!someoneLeft) {
            familyRepository.deleteFamily(familyId);
        }
    }
}
