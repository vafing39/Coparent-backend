package com.vynk.coparent_backend.user.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.dto.UserResponse;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    // emailVerified is read from User#isEmailVerified()
    UserResponse toResponse(User user);
}
