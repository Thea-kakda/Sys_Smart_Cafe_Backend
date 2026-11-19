package com.smartcoffee.common.exception;

public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(String message) {
        super("AUTH_UNAUTHORIZED", message, 401);
    }

    public UnauthorizedException(String code, String message) {
        super(code, message, 401);
    }
}

