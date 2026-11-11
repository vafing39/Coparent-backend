package com.vynk.coparent_backend.user.service;

import com.vynk.coparent_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import com.vynk.coparent_backend.user.repository.UserRepository;
import com.vynk.coparent_backend.user.entity.User;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** Loads the user behind a valid access token. A deleted or deactivated account is treated as signed out. */
    @Transactional(readOnly = true)
    public User getActiveUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Session invalide."));
    }
}
