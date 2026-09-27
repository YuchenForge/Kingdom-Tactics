package com.kingdom.api.dto;

import com.kingdom.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        // Same BCrypt 72-byte ceiling as registration — avoid silent truncation on match.
        @NotBlank @MaxUtf8Bytes(max = 72) String password
) {
}
