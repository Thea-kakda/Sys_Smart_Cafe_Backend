package com.smartcoffee.common.api;

public record ValidationError(String field, String code, String message) {
}

