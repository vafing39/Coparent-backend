package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.Child;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByFamilyIdOrderByBirthDateAscCreatedAtAsc(UUID familyId);

    Optional<Child> findByIdAndFamilyId(UUID id, UUID familyId);
}
