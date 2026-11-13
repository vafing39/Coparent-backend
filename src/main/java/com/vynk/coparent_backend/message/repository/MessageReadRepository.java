package com.vynk.coparent_backend.message.repository;

import com.vynk.coparent_backend.message.entity.MessageRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageReadRepository extends JpaRepository<MessageRead, MessageRead.Key> {

    List<MessageRead> findByKeyMessageIdIn(Collection<UUID> messageIds);

    /**
     * Marque comme lus par {@code userId} les messages de l'autre parent jusqu'à {@code upToSeq} qui ne l'étaient
     * pas encore. Insertion seule : un message lu n'est jamais modifié.
     */
    @Modifying
    @Query(value = """
            INSERT INTO message_reads (message_id, user_id, read_at)
            SELECT m.id, :userId, :readAt FROM messages m
            WHERE m.family_id = :familyId AND m.seq <= :upToSeq
              AND (m.sender_id IS NULL OR m.sender_id <> :userId)
              AND NOT EXISTS (SELECT 1 FROM message_reads r WHERE r.message_id = m.id AND r.user_id = :userId)
            """, nativeQuery = true)
    int markRead(@Param("familyId") UUID familyId, @Param("userId") UUID userId,
                 @Param("upToSeq") long upToSeq, @Param("readAt") Instant readAt);
}
