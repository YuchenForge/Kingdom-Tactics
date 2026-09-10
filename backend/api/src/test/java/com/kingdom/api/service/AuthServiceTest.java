package com.kingdom.api.service;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.LoginRequest;
import com.kingdom.api.dto.RegisterRequest;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.DuplicateUserException;
import com.kingdom.api.exception.InvalidCredentialsException;
import com.kingdom.api.exception.UserNotFoundException;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registerSucceedsAndHashesPassword() {
        RegisterRequest request = new RegisterRequest("alice", "Alice@Test.com", "password123");
        UUID userId = UUID.randomUUID();
        String token = "jwt-token";

        when(userRepository.existsByEmail("alice@test.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", userId);
            ReflectionTestUtils.invokeMethod(user, "onCreate");
            return user;
        });
        when(jwtService.generateToken(userId)).thenReturn(token);

        AuthResponse response = authService.register(request);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.email()).isEqualTo("alice@test.com");
        assertThat(response.token()).isEqualTo(token);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.expiresIn()).isNull();

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(savedUser.capture());

        byte[] storedHash = savedUser.getValue().getPasswordHash();
        String hashString = new String(storedHash, StandardCharsets.UTF_8);
        assertThat(hashString).isNotEqualTo("password123");
        assertThat(hashString).startsWith("$2");
        assertThat(passwordEncoder.matches("password123", hashString)).isTrue();
    }

    @Test
    void registerDuplicateEmailThrows() {
        RegisterRequest request = new RegisterRequest("alice", "a@test.com", "password123");
        when(userRepository.existsByEmail("a@test.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateUserException.class)
                .extracting(ex -> ((DuplicateUserException) ex).getField())
                .isEqualTo("email");
    }

    @Test
    void loginCorrectPasswordReturnsToken() {
        UUID userId = UUID.randomUUID();
        String hash = passwordEncoder.encode("password123");
        User user = new User("alice", "a@test.com", hash.getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(userId)).thenReturn("jwt-token");
        when(jwtService.expirationSeconds()).thenReturn(86_400L);

        AuthResponse response = authService.login(new LoginRequest("A@test.com", "password123"));

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.email()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.expiresIn()).isEqualTo(86_400L);
    }

    @Test
    void loginWrongPasswordThrows() {
        UUID userId = UUID.randomUUID();
        String hash = passwordEncoder.encode("password123");
        User user = new User("alice", "a@test.com", hash.getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@test.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void getCurrentUserMissingUserThrows() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getCurrentUser(userId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void registerUniqueRaceMapsToDuplicateEmail() {
        RegisterRequest request = new RegisterRequest("alice", "a@test.com", "password123");
        when(userRepository.existsByEmail("a@test.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique_users_email"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateUserException.class)
                .extracting(ex -> ((DuplicateUserException) ex).getField())
                .isEqualTo("email");
    }

    @Test
    void registerUniqueRaceMapsToDuplicateUsername() {
        RegisterRequest request = new RegisterRequest("alice", "a@test.com", "password123");
        when(userRepository.existsByEmail("a@test.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("users_username_key"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateUserException.class)
                .extracting(ex -> ((DuplicateUserException) ex).getField())
                .isEqualTo("username");
    }
}
