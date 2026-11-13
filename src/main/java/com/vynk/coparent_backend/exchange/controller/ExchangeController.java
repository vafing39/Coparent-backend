package com.vynk.coparent_backend.exchange.controller;

import com.vynk.coparent_backend.exchange.dto.ExchangeRequest;
import com.vynk.coparent_backend.exchange.dto.ExchangeResponse;
import com.vynk.coparent_backend.exchange.service.ExchangeService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Demandes d'échange de garde entre les deux parents. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/exchanges")
@RequiredArgsConstructor
public class ExchangeController {

    private final ExchangeService exchangeService;

    @GetMapping
    public List<ExchangeResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return exchangeService.list(familyId, userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @Valid @RequestBody ExchangeRequest request) {
        return exchangeService.create(familyId, userId(jwt), request);
    }

    @GetMapping("/{exchangeId}")
    public ExchangeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @PathVariable UUID exchangeId) {
        return exchangeService.get(familyId, userId(jwt), exchangeId);
    }

    @PostMapping("/{exchangeId}/accept")
    public ExchangeResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @PathVariable UUID exchangeId) {
        return exchangeService.accept(familyId, userId(jwt), exchangeId);
    }

    @PostMapping("/{exchangeId}/decline")
    public ExchangeResponse decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @PathVariable UUID exchangeId) {
        return exchangeService.decline(familyId, userId(jwt), exchangeId);
    }

    @PostMapping("/{exchangeId}/cancel")
    public ExchangeResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @PathVariable UUID exchangeId) {
        return exchangeService.cancel(familyId, userId(jwt), exchangeId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
