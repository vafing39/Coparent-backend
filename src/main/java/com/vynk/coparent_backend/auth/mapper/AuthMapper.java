package com.vynk.coparent_backend.auth.mapper;

import com.vynk.coparent_backend.auth.dto.AuthResponse;
import com.vynk.coparent_backend.user.mapper.UserMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.vynk.coparent_backend.auth.service.AuthSession;

@Mapper(uses = UserMapper.class)
public interface AuthMapper {

    @Mapping(target = "tokenType", constant = "Bearer")
    @Mapping(target = "accessToken", source = "accessToken.value")
    @Mapping(target = "accessTokenExpiresAt", source = "accessToken.expiresAt")
    AuthResponse toResponse(AuthSession session);
}
