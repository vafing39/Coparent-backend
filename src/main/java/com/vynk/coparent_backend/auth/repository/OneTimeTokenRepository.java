package com.vynk.coparent_backend.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.vynk.coparent_backend.auth.entity.OneTimeToken;
import com.vynk.coparent_backend.auth.entity.OneTimeTokenType;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OneTimeToken> findByTokenHashAndType(String tokenHash, OneTimeTokenType type);

    /** Invalidates earlier links when a new one is sent, so only the latest link works. */
    @Modifying
    @Query("update OneTimeToken t set t.usedAt = :now where t.user.id = :userId and t.type = :type and t.usedAt is null")
    int invalidateUnused(UUID userId, OneTimeTokenType type, Instant now);
}
