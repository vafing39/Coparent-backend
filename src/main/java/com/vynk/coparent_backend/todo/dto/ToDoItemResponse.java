package com.vynk.coparent_backend.todo.dto;

import com.vynk.coparent_backend.todo.entity.ToDoKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Une demande en attente. {@code requestedBy} : prénom de qui a demandé, null si le compte a été supprimé ;
 * {@code assignee} : prénom du parent qui doit répondre.
 */
public record ToDoItemResponse(
        UUID id,
        ToDoKind kind,
        String subject,
        String requestedBy,
        String assignee,
        Instant requestedAt,
        Instant lastNudgedAt
) {
}
