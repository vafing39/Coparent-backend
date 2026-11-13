package com.vynk.coparent_backend.user.repository;

import com.vynk.coparent_backend.user.entity.UserStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import com.vynk.coparent_backend.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Deleted accounts whose 30 days are over and whose personal data is still there. */
    List<User> findByStatusAndDeletionDueAtLessThanEqual(UserStatus status, Instant now);

    @Modifying
    @Query("delete from UserIdentity i where i.user.id = :userId")
    int deleteIdentities(@Param("userId") UUID userId);
}
