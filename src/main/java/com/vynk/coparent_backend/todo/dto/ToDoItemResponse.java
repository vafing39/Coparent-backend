package com.vynk.coparent_backend.todo.dto;

import com.vynk.coparent_backend.todo.entity.ToDoKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Une demande en attente. {@code targetId} : l'objet concerné (ex. l'échange de garde), null sinon ; {@code requestedBy} : prénom de qui a demandé, null si le compte a été supprimé ;
 * {@code assignee} : prénom du parent qui doit répondre.
 */
public record ToDoItemResponse(
        UUID id,
        ToDoKind kind,
        UUID targetId,
        String subject,
        String requestedBy,
        String assignee,
        Instant requestedAt,
        Instant lastNudgedAt
) {
}
