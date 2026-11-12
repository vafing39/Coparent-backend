package com.vynk.coparent_backend.family.controller;

import com.vynk.coparent_backend.family.dto.ChildRequest;
import com.vynk.coparent_backend.family.dto.ChildResponse;
import com.vynk.coparent_backend.family.dto.CustodyResponse;
import com.vynk.coparent_backend.family.dto.CustodyScheduleRequest;
import com.vynk.coparent_backend.family.dto.CustodyScheduleResponse;
import com.vynk.coparent_backend.family.dto.FamilyRequest;
import com.vynk.coparent_backend.family.dto.FamilyResponse;
import com.vynk.coparent_backend.family.dto.InvitationRequest;
import com.vynk.coparent_backend.family.dto.InvitationResponse;
import com.vynk.coparent_backend.family.service.ChildService;
import com.vynk.coparent_backend.family.service.CustodyScheduleService;
import com.vynk.coparent_backend.family.service.CustodyService;
import com.vynk.coparent_backend.family.service.FamilyService;
import com.vynk.coparent_backend.family.service.InvitationService;
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
import java.util.UUID;

/**
 * Première connexion (étapes 2 à 5) et garde calculée. Chaque route vérifie que l'utilisateur est membre
 * de la famille.
 */
@RestController
@RequestMapping("/api/v1/families")
@RequiredArgsConstructor
public class FamilyController {

    private final FamilyService familyService;
    private final ChildService childService;
    private final CustodyScheduleService scheduleService;
    private final InvitationService invitationService;
    private final CustodyService custodyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FamilyRequest request) {
        return familyService.create(userId(jwt), request);
    }

    @GetMapping("/me")
    public FamilyResponse getMine(@AuthenticationPrincipal Jwt jwt) {
        return familyService.getMine(userId(jwt));
    }

    @PutMapping("/{familyId}")
    public FamilyResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                 @Valid @RequestBody FamilyRequest request) {
        return familyService.update(familyId, userId(jwt), request);
    }

    @PostMapping("/{familyId}/children")
    @ResponseStatus(HttpStatus.CREATED)
    public ChildResponse addChild(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                  @Valid @RequestBody ChildRequest request) {
        return childService.add(familyId, userId(jwt), request);
    }

    @PutMapping("/{familyId}/children/{childId}")
    public ChildResponse updateChild(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                     @PathVariable UUID childId, @Valid @RequestBody ChildRequest request) {
        return childService.update(familyId, childId, userId(jwt), request);
    }

    @DeleteMapping("/{familyId}/children/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChild(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                            @PathVariable UUID childId) {
        childService.delete(familyId, childId, userId(jwt));
    }

    @PutMapping("/{familyId}/custody-schedule")
    public CustodyScheduleResponse proposeSchedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                                   @Valid @RequestBody CustodyScheduleRequest request) {
        return scheduleService.propose(familyId, userId(jwt), request);
    }

    /** Chez qui sont les enfants chaque nuit de {@code from} à {@code to} (92 jours au plus), et les passages. */
    @GetMapping("/{familyId}/custody")
    public CustodyResponse custody(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return custodyService.compute(familyId, userId(jwt), from, to);
    }

    @PostMapping("/{familyId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                     @Valid @RequestBody InvitationRequest request) {
        return invitationService.create(familyId, userId(jwt), request);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
