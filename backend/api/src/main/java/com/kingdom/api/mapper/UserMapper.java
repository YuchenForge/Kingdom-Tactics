package com.kingdom.api.mapper;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.UserResponse;
import com.kingdom.api.entity.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRating(),
                user.getCreatedAt());
    }

    public static AuthResponse toRegisterResponse(User user, String token) {
        return new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                token,
                user.getCreatedAt(),
                null);
    }

    public static AuthResponse toLoginResponse(User user, String token, long expiresIn) {
        return new AuthResponse(
                user.getId(),
                user.getUsername(),
                null,
                token,
                null,
                expiresIn);
    }
}
