package com.smartcoffee.common.exception;

public class InfrastructureException extends BusinessException {
    public InfrastructureException(String message) {
        super("SERVICE_TEMPORARILY_UNAVAILABLE", message, 503);
    }

    public InfrastructureException(String code, String message) {
        super(code, message, 503);
    }
}

