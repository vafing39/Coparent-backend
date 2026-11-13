package com.vynk.coparent_backend.message.repository;

import com.vynk.coparent_backend.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** Les plus récents d'abord. */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByConversationIdOrderBySeqDesc(UUID conversationId, Pageable page);

    /** Les plus récents d'abord, avant {@code seq}. */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByConversationIdAndSeqLessThanOrderBySeqDesc(UUID conversationId, long seq, Pageable page);

    /** Toute la chaîne de la famille dans l'ordre (vérification du hash chaîné). */
    @EntityGraph(attributePaths = {"sender", "conversation"})
    List<Message> findByFamilyIdOrderBySeqAsc(UUID familyId);

    Optional<Message> findFirstByFamilyIdOrderBySeqDesc(UUID familyId);

    /** Le dernier message de chaque discussion de la famille. */
    @Query("""
            select m from Message m left join fetch m.sender
            where m.family.id = :familyId
              and m.seq = (select max(other.seq) from Message other where other.conversation = m.conversation)
            """)
    List<Message> findLastOfEachConversation(@Param("familyId") UUID familyId);

    /** Nombre de messages de l'autre parent pas encore lus par {@code userId}, par discussion. */
    @Query("""
            select m.conversation.id, count(m) from Message m
            where m.family.id = :familyId and (m.sender is null or m.sender.id <> :userId)
              and not exists (select r from MessageRead r where r.key.messageId = m.id and r.key.userId = :userId)
            group by m.conversation.id
            """)
    List<Object[]> countUnreadByConversation(@Param("familyId") UUID familyId, @Param("userId") UUID userId);
}
