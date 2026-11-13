package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.Family;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface FamilyRepository extends JpaRepository<Family, UUID> {

    /** Verrouille la famille jusqu'à la fin de la transaction (messages envoyés l'un après l'autre). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Family f where f.id = :id")
    Optional<Family> findByIdForUpdate(UUID id);
}
