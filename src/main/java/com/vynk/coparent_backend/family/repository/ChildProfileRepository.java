package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.ChildProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChildProfileRepository extends JpaRepository<ChildProfile, UUID> {

    /** Fiches des enfants d'une famille (export de données). */
    @EntityGraph(attributePaths = {"child", "contacts"})
    List<ChildProfile> findByChildFamilyId(UUID familyId);
}
