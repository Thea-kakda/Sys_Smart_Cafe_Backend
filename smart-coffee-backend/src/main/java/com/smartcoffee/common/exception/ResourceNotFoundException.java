package com.smartcoffee.common.exception;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message, 404);
    }

    public ResourceNotFoundException(String code, String message) {
        super(code, message, 404);
    }
}

