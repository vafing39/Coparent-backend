package com.vynk.coparent_backend.exchange.repository;

import com.vynk.coparent_backend.exchange.entity.CustodyExchange;
import com.vynk.coparent_backend.exchange.entity.ExchangeStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustodyExchangeRepository extends JpaRepository<CustodyExchange, UUID> {

    @EntityGraph(attributePaths = "requestedBy")
    Optional<CustodyExchange> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Les échanges de la famille dans ces statuts, du plus ancien au plus récent. */
    @EntityGraph(attributePaths = "requestedBy")
    List<CustodyExchange> findByFamilyIdAndStatusInOrderByTakeFromAsc(UUID familyId, Collection<ExchangeStatus> statuses);
}
