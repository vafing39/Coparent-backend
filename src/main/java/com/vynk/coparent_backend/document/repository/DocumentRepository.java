package com.vynk.coparent_backend.document.repository;

import com.vynk.coparent_backend.document.entity.Document;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @EntityGraph(attributePaths = {"createdBy", "child"})
    Optional<Document> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Les documents de la famille, le plus récent d'abord (supprimés compris). */
    @EntityGraph(attributePaths = {"createdBy", "child"})
    List<Document> findByFamilyIdOrderByCreatedAtDesc(UUID familyId);
}
