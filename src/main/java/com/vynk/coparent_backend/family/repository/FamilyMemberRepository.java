package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.FamilyMember;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, UUID> {

    /** A user belongs to one family for now. */
    @EntityGraph(attributePaths = "family")
    Optional<FamilyMember> findFirstByUserId(UUID userId);

    @EntityGraph(attributePaths = "family")
    Optional<FamilyMember> findByFamilyIdAndUserId(UUID familyId, UUID userId);

    @EntityGraph(attributePaths = "user")
    List<FamilyMember> findByFamilyIdOrderByRoleAsc(UUID familyId);

    boolean existsByUserId(UUID userId);

    long countByFamilyId(UUID familyId);
}
