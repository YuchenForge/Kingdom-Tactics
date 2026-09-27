package com.kingdom.api.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /** Mirrors RegisterRequest password constraints for isolated bean-validation tests. */
    private record PasswordHolder(
            @NotBlank @Size(min = 8) @MaxUtf8Bytes(max = 72) String password) {
    }

    @Test
    void acceptsAsciiAtExactly72Bytes() {
        assertThat(validator.validate(new PasswordHolder("a".repeat(72)))).isEmpty();
    }

    @Test
    void rejectsAsciiAt73Bytes() {
        Set<ConstraintViolation<PasswordHolder>> violations =
                validator.validate(new PasswordHolder("a".repeat(73)));
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage())
                .contains("72 UTF-8 bytes");
    }

    @Test
    void acceptsMultibyteAtExactly72Bytes() {
        // U+00E9 (é) is 2 UTF-8 bytes → 36 chars = 72 bytes
        assertThat(validator.validate(new PasswordHolder("é".repeat(36)))).isEmpty();
    }

    @Test
    void rejectsMultibyteOver72BytesDespiteFewerThan72Chars() {
        // 37 × é = 74 bytes, only 37 characters — @Size(max=72) alone would miss this
        String password = "é".repeat(37);
        assertThat(password.length()).isEqualTo(37);
        Set<ConstraintViolation<PasswordHolder>> violations =
                validator.validate(new PasswordHolder(password));
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage())
                .contains("72 UTF-8 bytes");
    }

    @Test
    void preservesMinimumLengthRequirement() {
        Set<ConstraintViolation<PasswordHolder>> violations =
                validator.validate(new PasswordHolder("short"));
        assertThat(violations).isNotEmpty();
    }
}
