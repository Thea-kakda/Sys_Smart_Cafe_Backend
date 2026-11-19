package com.smartcoffee.common.exception;

public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super("VALIDATION_ERROR", message, 400);
    }

    public ValidationException(String code, String message) {
        super(code, message, 400);
    }
}

