package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.FamilyRelative;
import com.vynk.coparent_backend.family.entity.RelativeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyRelativeRepository extends JpaRepository<FamilyRelative, UUID> {

    @EntityGraph(attributePaths = "user")
    List<FamilyRelative> findByFamilyIdAndStatusInOrderByCreatedAtAsc(UUID familyId,
                                                                       Collection<RelativeStatus> statuses);

    Optional<FamilyRelative> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Le proche actif qu'est ce compte dans cette famille. */
    @EntityGraph(attributePaths = {"family", "user"})
    Optional<FamilyRelative> findByFamilyIdAndUserIdAndStatus(UUID familyId, UUID userId, RelativeStatus status);

    /** Les familles où ce compte est un proche. */
    @EntityGraph(attributePaths = "family")
    List<FamilyRelative> findByUserIdAndStatusOrderByJoinedAtAsc(UUID userId, RelativeStatus status);

    boolean existsByFamilyIdAndEmailAndStatusIn(UUID familyId, String email, Collection<RelativeStatus> statuses);

    Optional<FamilyRelative> findByTokenHash(String tokenHash);

    /** Verrouille l'invitation pendant qu'elle est acceptée (deux acceptations ne peuvent pas réussir toutes deux). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FamilyRelative r where r.tokenHash = :tokenHash")
    Optional<FamilyRelative> findByTokenHashForUpdate(String tokenHash);

    /** Suppression de compte : les familles où il est encore un proche. */
    List<FamilyRelative> findByUserIdAndStatusIn(UUID userId, Collection<RelativeStatus> statuses);
}
