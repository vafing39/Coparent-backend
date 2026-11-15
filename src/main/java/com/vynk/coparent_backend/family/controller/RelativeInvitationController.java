package com.vynk.coparent_backend.family.controller;

import com.vynk.coparent_backend.family.dto.RelativeFamilyResponse;
import com.vynk.coparent_backend.family.dto.RelativeInvitationPreviewResponse;
import com.vynk.coparent_backend.family.service.RelativeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Famille élargie, côté proche : aperçu public de l'invitation, rejoindre, ses familles, quitter. */
@RestController
@RequiredArgsConstructor
public class RelativeInvitationController {

    private final RelativeService relativeService;

    @GetMapping("/api/v1/relative-invitations/{token}")
    public RelativeInvitationPreviewResponse preview(@PathVariable String token) {
        return relativeService.preview(token);
    }

    @PostMapping("/api/v1/relative-invitations/{token}/accept")
    public RelativeFamilyResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return relativeService.accept(token, userId(jwt));
    }

    @GetMapping("/api/v1/users/me/relative-families")
    public List<RelativeFamilyResponse> myFamilies(@AuthenticationPrincipal Jwt jwt) {
        return relativeService.myFamilies(userId(jwt));
    }

    @DeleteMapping("/api/v1/users/me/relative-families/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        relativeService.leave(familyId, userId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
