package com.smartcoffee.common.exception;

public class ConflictException extends BusinessException {
    public ConflictException(String message) {
        super("CONFLICT", message, 409);
    }

    public ConflictException(String code, String message) {
        super(code, message, 409);
    }
}

