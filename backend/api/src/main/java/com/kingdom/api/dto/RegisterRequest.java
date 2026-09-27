package com.kingdom.api.dto;

import com.kingdom.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @NotBlank @Email @Size(max = 100) String email,
        // BCrypt uses at most 72 UTF-8 bytes; reject longer passwords instead of truncating.
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(max = 72) String password
) {
}
