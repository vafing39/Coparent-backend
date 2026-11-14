package com.vynk.coparent_backend.document.repository;

import com.vynk.coparent_backend.document.entity.DocumentEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentEventRepository extends JpaRepository<DocumentEvent, UUID> {

    /** L'historique de tous les documents de la famille, le plus récent d'abord. */
    @EntityGraph(attributePaths = {"user", "document"})
    List<DocumentEvent> findByDocumentFamilyIdOrderByCreatedAtDesc(UUID familyId);
}
