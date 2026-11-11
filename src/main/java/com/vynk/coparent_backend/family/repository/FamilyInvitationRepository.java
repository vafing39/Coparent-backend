package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.FamilyInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FamilyInvitationRepository extends JpaRepository<FamilyInvitation, UUID> {

    Optional<FamilyInvitation> findByTokenHash(String tokenHash);

    /** Locks the invitation while it is being accepted (two acceptances cannot both succeed). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FamilyInvitation i where i.tokenHash = :tokenHash")
    Optional<FamilyInvitation> findByTokenHashForUpdate(String tokenHash);

    /** A new link replaces the previous unused ones. */
    @Modifying
    @Query("update FamilyInvitation i set i.revokedAt = :now "
            + "where i.family.id = :familyId and i.acceptedAt is null and i.revokedAt is null")
    int revokePending(UUID familyId, Instant now);
}
