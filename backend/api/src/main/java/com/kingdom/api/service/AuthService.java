package com.kingdom.api.service;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.LoginRequest;
import com.kingdom.api.dto.RegisterRequest;
import com.kingdom.api.dto.UserResponse;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.DuplicateUserException;
import com.kingdom.api.exception.InvalidCredentialsException;
import com.kingdom.api.exception.UserNotFoundException;
import com.kingdom.api.mapper.UserMapper;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateUserException("email");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException("username");
        }

        User user = new User(
                request.username(),
                email,
                passwordEncoder.encode(request.password()).getBytes(StandardCharsets.UTF_8));

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(email) / UNIQUE(username) race — another request won the insert.
            // Do not re-query: Postgres has aborted this transaction after DIV.
            // Only map known uniqueness violations; other integrity errors (e.g. length)
            // must not be misreported as duplicates.
            Optional<String> field = uniqueConflictField(e);
            if (field.isPresent()) {
                log.info("Registration rejected: unique constraint race");
                throw new DuplicateUserException(field.get());
            }
            throw e;
        }

        MDC.put("userId", user.getId().toString());
        log.info("User registered");

        String token = jwtService.generateToken(user.getId());
        return UserMapper.toRegisterResponse(user, token);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase(Locale.ROOT))
                .orElseThrow(InvalidCredentialsException::new);

        String hash = new String(user.getPasswordHash(), StandardCharsets.UTF_8);
        if (!passwordEncoder.matches(request.password(), hash)) {
            throw new InvalidCredentialsException();
        }

        MDC.put("userId", user.getId().toString());
        log.info("User logged in");

        String token = jwtService.generateToken(user.getId());
        return UserMapper.toLoginResponse(user, token, jwtService.expirationSeconds());
    }

    public UserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return UserMapper.toUserResponse(user);
    }

    /**
     * Maps only known UNIQUE constraint races to a conflict field. No DB access.
     * Returns empty for non-uniqueness integrity failures (e.g. value too long).
     */
    private static Optional<String> uniqueConflictField(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (detail.contains("users_username_key")
                || (detail.contains("unique") && detail.contains("username"))) {
            return Optional.of("username");
        }
        if (detail.contains("users_email_key")
                || (detail.contains("unique") && detail.contains("email"))) {
            return Optional.of("email");
        }
        return Optional.empty();
    }
}
