package com.vynk.coparent_backend.feed.controller;

import com.vynk.coparent_backend.feed.dto.AlbumResponse;
import com.vynk.coparent_backend.feed.service.AlbumService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Album d'un enfant : les photos du fil (parents et proches actifs). */
@RestController
@RequestMapping("/api/v1/families/{familyId}/album")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @GetMapping
    public AlbumResponse album(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                               @RequestParam UUID childId, @RequestParam(required = false) Integer year) {
        return albumService.album(familyId, UUID.fromString(jwt.getSubject()), childId, year);
    }
}
