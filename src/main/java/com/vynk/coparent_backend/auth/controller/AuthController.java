package com.vynk.coparent_backend.auth.controller;

import com.vynk.coparent_backend.auth.dto.AuthResponse;
import com.vynk.coparent_backend.auth.dto.ForgotPasswordRequest;
import com.vynk.coparent_backend.auth.dto.LoginRequest;
import com.vynk.coparent_backend.auth.dto.RefreshTokenRequest;
import com.vynk.coparent_backend.auth.dto.ResetPasswordRequest;
import com.vynk.coparent_backend.auth.dto.SignupRequest;
import com.vynk.coparent_backend.auth.dto.TokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import com.vynk.coparent_backend.auth.service.AuthService;
import com.vynk.coparent_backend.auth.mapper.AuthMapper;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthMapper authMapper;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        return authMapper.toResponse(authService.signup(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authMapper.toResponse(authService.login(request, http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authMapper.toResponse(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        authService.verifyEmail(request.token());
    }

    @PostMapping("/email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendEmailVerification(@AuthenticationPrincipal Jwt jwt) {
        authService.resendEmailVerification(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        authService.forgotPassword(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
