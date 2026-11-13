package com.vynk.coparent_backend.event.controller;

import com.vynk.coparent_backend.event.dto.EventRequest;
import com.vynk.coparent_backend.event.dto.EventResponse;
import com.vynk.coparent_backend.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Événements de la famille. Un événement privé n'est visible que par son auteur. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return eventService.list(familyId, userId(jwt), from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @Valid @RequestBody EventRequest request) {
        return eventService.create(familyId, userId(jwt), request);
    }

    @GetMapping("/{eventId}")
    public EventResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                             @PathVariable UUID eventId) {
        return eventService.get(familyId, userId(jwt), eventId);
    }

    @PutMapping("/{eventId}")
    public EventResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @PathVariable UUID eventId, @Valid @RequestBody EventRequest request) {
        return eventService.update(familyId, userId(jwt), eventId, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                       @PathVariable UUID eventId) {
        eventService.delete(familyId, userId(jwt), eventId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
