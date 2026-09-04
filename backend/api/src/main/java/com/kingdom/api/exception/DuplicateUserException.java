package com.kingdom.api.exception;

public class DuplicateUserException extends RuntimeException {

    private final String field;

    public DuplicateUserException(String field) {
        super("User already exists: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
