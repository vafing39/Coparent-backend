package com.vynk.coparent_backend.todo.controller;

import com.vynk.coparent_backend.todo.dto.ToDoResponse;
import com.vynk.coparent_backend.todo.service.ToDoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** « À traiter » : ce qui attend une réponse, de moi ou de l'autre parent. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/to-do")
@RequiredArgsConstructor
public class ToDoController {

    private final ToDoService toDoService;

    @GetMapping
    public ToDoResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return toDoService.list(familyId, UUID.fromString(jwt.getSubject()));
    }

    /** Relance l'autre parent : une fois par jour au maximum (429 sinon). Renvoie « À traiter » à jour. */
    @PostMapping("/{itemId}/nudge")
    public ToDoResponse nudge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                              @PathVariable UUID itemId) {
        return toDoService.nudge(familyId, UUID.fromString(jwt.getSubject()), itemId);
    }
}
