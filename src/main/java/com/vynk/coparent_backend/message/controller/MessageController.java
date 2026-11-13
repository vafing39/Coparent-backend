package com.vynk.coparent_backend.message.controller;

import com.vynk.coparent_backend.message.dto.ConversationResponse;
import com.vynk.coparent_backend.message.dto.CreateConversationRequest;
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

import java.util.List;
import java.util.UUID;

/** Discussions par sujet entre parents : ni modification ni suppression, par conception. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/conversations")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping
    public List<ConversationResponse> conversations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return messageService.conversations(familyId, userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                       @Valid @RequestBody CreateConversationRequest request) {
        return messageService.createConversation(familyId, userId(jwt), request.subject(), request.body());
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePageResponse messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                        @PathVariable UUID conversationId,
                                        @RequestParam(required = false) Long before,
                                        @RequestParam(required = false) Integer limit) {
        return messageService.list(familyId, userId(jwt), conversationId, before, limit);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request) {
        return messageService.send(familyId, userId(jwt), conversationId, request.body());
    }

    @PostMapping("/{conversationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                     @PathVariable UUID conversationId, @Valid @RequestBody ReadRequest request) {
        messageService.markRead(familyId, userId(jwt), conversationId, request.upToSeq());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
