package com.smartcoffee.common.api;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(boolean success, ErrorBody error, String requestId, Instant timestamp) {
  public record ErrorBody(String code, String message, List<ValidationError> details) {
  }

  public static ApiErrorResponse of(String code, String message, List<ValidationError> details, String requestId) {
    return new ApiErrorResponse(false, new ErrorBody(code, message, details), requestId, Instant.now());
  }
}

