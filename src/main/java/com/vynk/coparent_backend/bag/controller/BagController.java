package com.vynk.coparent_backend.bag.controller;

import com.vynk.coparent_backend.bag.dto.BagItemRequest;
import com.vynk.coparent_backend.bag.dto.BagResponse;
import com.vynk.coparent_backend.bag.service.BagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Sac à préparer pour le prochain passage. Chaque modification renvoie le sac entier. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/bag")
@RequiredArgsConstructor
public class BagController {

    private final BagService bagService;

    @GetMapping
    public BagResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return bagService.get(familyId, userId(jwt));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public BagResponse addItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                               @Valid @RequestBody BagItemRequest request) {
        return bagService.addItem(familyId, userId(jwt), request);
    }

    @DeleteMapping("/items/{itemId}")
    public BagResponse deleteItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                  @PathVariable UUID itemId) {
        return bagService.deleteItem(familyId, userId(jwt), itemId);
    }

    @PutMapping("/items/{itemId}/check")
    public BagResponse check(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                             @PathVariable UUID itemId) {
        return bagService.check(familyId, userId(jwt), itemId);
    }

    @DeleteMapping("/items/{itemId}/check")
    public BagResponse uncheck(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                               @PathVariable UUID itemId) {
        return bagService.uncheck(familyId, userId(jwt), itemId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
