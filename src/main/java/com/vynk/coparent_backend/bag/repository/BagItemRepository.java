package com.vynk.coparent_backend.bag.repository;

import com.vynk.coparent_backend.bag.entity.BagItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BagItemRepository extends JpaRepository<BagItem, UUID> {

    List<BagItem> findByFamilyIdOrderByCreatedAtAsc(UUID familyId);

    Optional<BagItem> findByIdAndFamilyId(UUID id, UUID familyId);
}
