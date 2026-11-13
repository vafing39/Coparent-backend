package com.vynk.coparent_backend.message.repository;

import com.vynk.coparent_backend.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** Les plus récents d'abord. */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByFamilyIdOrderBySeqDesc(UUID familyId, Pageable page);

    /** Les plus récents d'abord, avant {@code seq}. */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByFamilyIdAndSeqLessThanOrderBySeqDesc(UUID familyId, long seq, Pageable page);

    /** Toute la conversation dans l'ordre (vérification du hash chaîné). */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByFamilyIdOrderBySeqAsc(UUID familyId);

    Optional<Message> findFirstByFamilyIdOrderBySeqDesc(UUID familyId);
}
