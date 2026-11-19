package com.smartcoffee.common.exception;

public class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) {
        super("AUTH_FORBIDDEN", message, 403);
    }

    public ForbiddenException(String code, String message) {
        super(code, message, 403);
    }
}

