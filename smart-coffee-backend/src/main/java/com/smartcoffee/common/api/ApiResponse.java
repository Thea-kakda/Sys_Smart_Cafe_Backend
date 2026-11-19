package com.smartcoffee.common.api;

import java.time.Instant;

public record ApiResponse<T>(boolean success, T data, String requestId, Instant timestamp) {
  public static <T> ApiResponse<T> ok(T data, String requestId) {
    return new ApiResponse<>(true, data, requestId, Instant.now());
  }
}

