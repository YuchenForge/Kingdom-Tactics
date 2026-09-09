package com.kingdom.api.service;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.LoginRequest;
import com.kingdom.api.dto.RegisterRequest;
import com.kingdom.api.dto.UserResponse;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.DuplicateUserException;
import com.kingdom.api.exception.InvalidCredentialsException;
import com.kingdom.api.mapper.UserMapper;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
        String email = request.email().toLowerCase();
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

        user = userRepository.save(user);
        MDC.put("userId", user.getId().toString());
        log.info("User registered");

        String token = jwtService.generateToken(user.getId());
        return UserMapper.toRegisterResponse(user, token);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
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
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return UserMapper.toUserResponse(user);
    }
}
