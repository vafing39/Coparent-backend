package com.vynk.coparent_backend.family.controller;

import com.vynk.coparent_backend.family.dto.RelativeRequest;
import com.vynk.coparent_backend.family.dto.RelativeResponse;
import com.vynk.coparent_backend.family.service.RelativeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Famille élargie, côté parents : proposer, valider ou refuser, renvoyer le lien, retirer. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/relatives")
@RequiredArgsConstructor
public class RelativeController {

    private final RelativeService relativeService;

    @GetMapping
    public List<RelativeResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return relativeService.list(familyId, userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RelativeResponse propose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @Valid @RequestBody RelativeRequest request) {
        return relativeService.propose(familyId, userId(jwt), request);
    }

    @PostMapping("/{relativeId}/approve")
    public RelativeResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @PathVariable UUID relativeId) {
        return relativeService.approve(familyId, userId(jwt), relativeId);
    }

    @PostMapping("/{relativeId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                        @PathVariable UUID relativeId) {
        relativeService.decline(familyId, userId(jwt), relativeId);
    }

    @PostMapping("/{relativeId}/resend")
    public RelativeResponse resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @PathVariable UUID relativeId) {
        return relativeService.resend(familyId, userId(jwt), relativeId);
    }

    @DeleteMapping("/{relativeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId, @PathVariable UUID relativeId) {
        relativeService.remove(familyId, userId(jwt), relativeId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
