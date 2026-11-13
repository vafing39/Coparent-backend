package com.vynk.coparent_backend.message.repository;

import com.vynk.coparent_backend.message.entity.Conversation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @EntityGraph(attributePaths = "author")
    List<Conversation> findByFamilyId(UUID familyId);

    @EntityGraph(attributePaths = "author")
    Optional<Conversation> findByIdAndFamilyId(UUID id, UUID familyId);
}
