package com.vynk.coparent_backend.event.repository;

import com.vynk.coparent_backend.event.entity.EventChange;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventChangeRepository extends JpaRepository<EventChange, UUID> {

    /** Historique d'une série, le plus récent d'abord. */
    @EntityGraph(attributePaths = {"author"})
    List<EventChange> findByFamilyIdAndSeriesIdOrderByCreatedAtDescIdDesc(UUID familyId, UUID seriesId);
}
