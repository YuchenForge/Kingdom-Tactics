package com.kingdom.api.exception;

import com.kingdom.api.dto.ApiError;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElse("Validation failed");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateUserException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("CONFLICT", ex.getField() + " already exists", HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> badCredentials() {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("UNAUTHORIZED", "Invalid email or password", HttpStatus.UNAUTHORIZED.value()));
    }

    private String formatFieldError(FieldError fieldError) {
        String field = fieldError.getField();
        String defaultMessage = fieldError.getDefaultMessage();
        if (defaultMessage == null || defaultMessage.isBlank()) {
            return field + " is invalid";
        }
        return field + " " + defaultMessage;
    }

    private ApiError error(String code, String message, int status) {
        return new ApiError(code, message, status, Instant.now(), requestId());
    }

    private String requestId() {
        return MDC.get("requestId");
    }
}
