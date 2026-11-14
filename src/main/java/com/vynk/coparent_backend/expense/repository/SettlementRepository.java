package com.vynk.coparent_backend.expense.repository;

import com.vynk.coparent_backend.expense.entity.Settlement;
import com.vynk.coparent_backend.expense.entity.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Les virements de la famille dans ces statuts, le plus récent d'abord. */
    List<Settlement> findByFamilyIdAndStatusInOrderByDeclaredAtDesc(UUID familyId,
                                                                   Collection<SettlementStatus> statuses);
}
