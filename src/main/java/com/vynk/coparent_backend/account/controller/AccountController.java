package com.vynk.coparent_backend.account.controller;

import com.vynk.coparent_backend.account.dto.ChangePasswordRequest;
import com.vynk.coparent_backend.account.dto.DeleteAccountRequest;
import com.vynk.coparent_backend.account.service.AccountService;
import com.vynk.coparent_backend.account.service.DataExportService;
import com.vynk.coparent_backend.auth.dto.AuthResponse;
import com.vynk.coparent_backend.auth.mapper.AuthMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** « Mon compte » (spec §1): password, personal data, account deletion. */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final DataExportService dataExportService;
    private final AuthMapper authMapper;

    @PostMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authMapper.toResponse(accountService.changePassword(UUID.fromString(jwt.getSubject()), request));
    }

    @PostMapping("/data-export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestDataExport(@AuthenticationPrincipal Jwt jwt) {
        dataExportService.sendExport(UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
        accountService.deleteAccount(UUID.fromString(jwt.getSubject()), request.password());
    }
}
