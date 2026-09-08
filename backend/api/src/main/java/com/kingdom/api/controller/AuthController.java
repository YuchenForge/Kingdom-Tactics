package com.kingdom.api.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kingdom.api.dto.AuthResponse;
import com.kingdom.api.dto.LoginRequest;
import com.kingdom.api.dto.RegisterRequest;
import com.kingdom.api.dto.UserResponse;
import com.kingdom.api.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // Registers a new user account and returns an auth token 
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // Authenticates an existing user with credentials and returns an auth token
    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // Returns the profile of the currently authenticated user
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return authService.getCurrentUser(userId);
    }
}
