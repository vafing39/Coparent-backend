package com.vynk.coparent_backend.message.controller;

import com.vynk.coparent_backend.message.dto.MessagePageResponse;
import com.vynk.coparent_backend.message.dto.MessageResponse;
import com.vynk.coparent_backend.message.dto.ReadRequest;
import com.vynk.coparent_backend.message.dto.SendMessageRequest;
import com.vynk.coparent_backend.message.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Messagerie entre parents : pas de modification ni de suppression, par conception. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping
    public MessagePageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @RequestParam(required = false) Long before,
                                    @RequestParam(required = false) Integer limit) {
        return messageService.list(familyId, userId(jwt), before, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @Valid @RequestBody SendMessageRequest request) {
        return messageService.send(familyId, userId(jwt), request.body());
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                     @Valid @RequestBody ReadRequest request) {
        messageService.markRead(familyId, userId(jwt), request.upToSeq());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
