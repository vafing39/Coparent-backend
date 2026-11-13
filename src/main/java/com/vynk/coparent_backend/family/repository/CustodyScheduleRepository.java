package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustodyScheduleRepository extends JpaRepository<CustodySchedule, UUID> {

    Optional<CustodySchedule> findFirstByFamilyIdAndStatusOrderByCreatedAtDesc(UUID familyId, ScheduleStatus status);

    Optional<CustodySchedule> findFirstByFamilyIdAndStatusAndProposedByIdOrderByCreatedAtDesc(
            UUID familyId, ScheduleStatus status, UUID proposedById);

    Optional<CustodySchedule> findByIdAndFamilyId(UUID id, UUID familyId);

    List<CustodySchedule> findByFamilyIdAndStatus(UUID familyId, ScheduleStatus status);
}
