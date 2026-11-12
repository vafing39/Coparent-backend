package com.vynk.coparent_backend.bag.repository;

import com.vynk.coparent_backend.bag.entity.BagCheck;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BagCheckRepository extends JpaRepository<BagCheck, UUID> {

    @EntityGraph(attributePaths = "checkedBy")
    List<BagCheck> findByItemIdInAndHandoverAt(Collection<UUID> itemIds, Instant handoverAt);

    boolean existsByItemIdAndHandoverAt(UUID itemId, Instant handoverAt);

    void deleteByItemIdAndHandoverAt(UUID itemId, Instant handoverAt);
}
