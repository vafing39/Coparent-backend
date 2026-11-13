package com.vynk.coparent_backend.todo.repository;

import com.vynk.coparent_backend.todo.entity.ToDoItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToDoItemRepository extends JpaRepository<ToDoItem, UUID> {

    /** Demandes sans réponse, les plus anciennes d'abord. */
    @EntityGraph(attributePaths = {"requestedBy", "assignee"})
    List<ToDoItem> findByFamilyIdAndResolvedAtIsNullOrderByRequestedAtAsc(UUID familyId);

    /** Une demande sans réponse de cette famille. */
    @EntityGraph(attributePaths = {"requestedBy", "assignee"})
    Optional<ToDoItem> findByIdAndFamilyIdAndResolvedAtIsNull(UUID id, UUID familyId);
}
