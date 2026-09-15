package com.kingdom.api.exception;

/** Concurrent update / idempotency race — client may retry with the same key. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
