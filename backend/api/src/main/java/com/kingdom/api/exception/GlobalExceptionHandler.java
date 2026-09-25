package com.kingdom.api.exception;

import com.kingdom.api.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatFieldError)
                .orElse("Validation failed");

        log.info("Validation failed: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> badJson(HttpMessageNotReadableException ex) {
        log.info("Malformed request body");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VALIDATION_ERROR", "Malformed request body", HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateUserException ex) {
        log.info("Registration conflict: {} already exists", ex.getField());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("CONFLICT", ex.getField() + " already exists", HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> badCredentials() {
        log.info("Login rejected: invalid credentials");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("UNAUTHORIZED", "Invalid email or password", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> userNotFound(UserNotFoundException ex) {
        log.info("Authenticated user missing from database");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("UNAUTHORIZED", "User not found", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ApiError> gameNotFound(GameNotFoundException ex) {
        log.info("Game not found");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("GAME_NOT_FOUND", "Game not found", HttpStatus.NOT_FOUND.value()));
    }

    @ExceptionHandler(NotGameParticipantException.class)
    public ResponseEntity<ApiError> notParticipant(NotGameParticipantException ex) {
        log.info("Access denied: not a game participant");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(error(
                        "NOT_GAME_PARTICIPANT",
                        "You are not a participant of this game",
                        HttpStatus.FORBIDDEN.value()));
    }

    @ExceptionHandler(AlreadyInGameException.class)
    public ResponseEntity<ApiError> alreadyInGame(AlreadyInGameException ex) {
        log.info("Join rejected: already in game");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("ALREADY_IN_GAME", "You are already in this game", HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(GameFullException.class)
    public ResponseEntity<ApiError> gameFull(GameFullException ex) {
        log.info("Join rejected: game full");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("GAME_FULL", "Game is full or no longer joinable", HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(GameNotReadyException.class)
    public ResponseEntity<ApiError> gameNotReady(GameNotReadyException ex) {
        log.info("State rejected: game not ready");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("GAME_NOT_READY", "Game is still waiting for players", HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex) {
        String header = ex.getHeaderName();
        log.info("Missing request header: {}", header);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error(
                        "VALIDATION_ERROR",
                        "Missing required header: " + header,
                        HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("Invalid request parameter: {}", ex.getName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error(
                        "VALIDATION_ERROR",
                        "Invalid value for " + ex.getName(),
                        HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParam(MissingServletRequestParameterException ex) {
        log.info("Missing request parameter: {}", ex.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error(
                        "VALIDATION_ERROR",
                        "Missing required parameter: " + ex.getParameterName(),
                        HttpStatus.BAD_REQUEST.value()));
    }

    /** Service-level query/path validation (e.g. round out of 1..8, limit < 1). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        log.info("Validation failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VALIDATION_ERROR", ex.getMessage(), HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(PlanningCommandException.class)
    public ResponseEntity<ApiError> planningCommand(PlanningCommandException ex) {
        int status = ex.httpStatus();
        log.info("Planning command rejected: {} ({})", ex.errorCode(), status);
        return ResponseEntity
                .status(status)
                .body(error(ex.errorCode(), ex.getMessage(), status));
    }

    @ExceptionHandler(RoundLockedException.class)
    public ResponseEntity<ApiError> roundLocked(RoundLockedException ex) {
        log.info("Round locked");
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(error("LOCKED", "Round is locked, cannot modify", HttpStatus.LOCKED.value()));
    }

    @ExceptionHandler(WrongGameStateException.class)
    public ResponseEntity<ApiError> wrongGameState(WrongGameStateException ex) {
        log.info("Wrong game state: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("WRONG_GAME_STATE", ex.getMessage(), HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(RoundNotFoundException.class)
    public ResponseEntity<ApiError> roundNotFound(RoundNotFoundException ex) {
        log.info("Round not found");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("ROUND_NOT_FOUND", "Round not found", HttpStatus.NOT_FOUND.value()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        log.info("Conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error(ex.getCode(), ex.getMessage(), HttpStatus.CONFLICT.value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Unhandled exception", ex);  // full stack in logs only
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        HttpStatus.INTERNAL_SERVER_ERROR.value()));
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
