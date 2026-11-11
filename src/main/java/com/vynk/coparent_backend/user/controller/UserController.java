package com.vynk.coparent_backend.user.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import com.vynk.coparent_backend.user.service.UserService;
import com.vynk.coparent_backend.user.mapper.UserMapper;
import com.vynk.coparent_backend.user.dto.UserResponse;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userMapper.toResponse(userService.getActiveUser(UUID.fromString(jwt.getSubject())));
    }
}
