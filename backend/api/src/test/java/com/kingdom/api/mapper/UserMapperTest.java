package com.kingdom.api.mapper;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.UserResponse;
import com.kingdom.api.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    @Test
    void toUserResponseMapsPublicFieldsOnly() {
        User user = user("alice", "alice@test.com");

        UserResponse response = UserMapper.toUserResponse(user);

        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.email()).isEqualTo("alice@test.com");
        assertThat(response.rating()).isEqualTo(1200);
        assertThat(response.createdAt()).isEqualTo(user.getCreatedAt());
    }

    @Test
    void toRegisterResponseIncludesEmailAndCreatedAtOmitsExpiresIn() {
        User user = user("bob", "bob@test.com");

        AuthResponse response = UserMapper.toRegisterResponse(user, "tok");

        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.email()).isEqualTo("bob@test.com");
        assertThat(response.token()).isEqualTo("tok");
        assertThat(response.createdAt()).isEqualTo(user.getCreatedAt());
        assertThat(response.expiresIn()).isNull();
    }

    @Test
    void toLoginResponseIncludesExpiresInOmitsEmailAndCreatedAt() {
        User user = user("carol", "carol@test.com");

        AuthResponse response = UserMapper.toLoginResponse(user, "tok", 3600L);

        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.username()).isEqualTo("carol");
        assertThat(response.token()).isEqualTo("tok");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.email()).isNull();
        assertThat(response.createdAt()).isNull();
    }

    private static User user(String username, String email) {
        User user = new User(username, email, "hash".getBytes());
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2024-01-15T14:23:45Z"));
        return user;
    }
}
