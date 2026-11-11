package com.vynk.coparent_backend.family.controller;

import com.vynk.coparent_backend.family.dto.FamilyResponse;
import com.vynk.coparent_backend.family.dto.InvitationPreviewResponse;
import com.vynk.coparent_backend.family.service.InvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Côté invité : aperçu public de l'invitation, puis acceptation une fois connecté. */
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @GetMapping("/{token}")
    public InvitationPreviewResponse preview(@PathVariable String token) {
        return invitationService.preview(token);
    }

    @PostMapping("/{token}/accept")
    public FamilyResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return invitationService.accept(token, UUID.fromString(jwt.getSubject()));
    }
}
