package com.smartcoffee.common.api;

import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> business(BusinessException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiErrorResponse.of(ex.code(), ex.getMessage(), List.of(), CorrelationContext.requestId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ValidationError(e.getField(), "INVALID_VALUE", e.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_ERROR", "Request validation failed",
                details, CorrelationContext.requestId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_ERROR", "Request validation failed",
                List.of(), CorrelationContext.requestId()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> optimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(409).body(ApiErrorResponse.of("VERSION_CONFLICT",
                "This record was updated by another user. Refresh the data before editing again.", List.of(),
                CorrelationContext.requestId()));
    }

    /** Malformed JSON bodies must surface as 400, never as an internal error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("MALFORMED_REQUEST",
                "Request body is missing or could not be parsed", List.of(), CorrelationContext.requestId()));
    }

    /** Invalid path/query parameter types (e.g. non-UUID storeId) map to 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_ERROR",
                "Request parameter '" + ex.getName() + "' is not valid", List.of(), CorrelationContext.requestId()));
    }

    /**
     * A required cookie is absent (e.g. POST /auth/refresh without a session
     * cookie). This is an unauthenticated state, not a server fault.
     */
    @ExceptionHandler(MissingRequestCookieException.class)
    ResponseEntity<ApiErrorResponse> missingCookie(MissingRequestCookieException ex) {
        return ResponseEntity.status(401).body(ApiErrorResponse.of("UNAUTHENTICATED",
                "Authentication is required for this request", List.of(), CorrelationContext.requestId()));
    }

    /**
     * A multipart upload larger than {@code spring.servlet.multipart.max-file-size}
     * is rejected by Spring BEFORE it reaches any controller. Map it to the same
     * FILE_TOO_LARGE contract the storage layer uses for oversize files, so the
     * frontend gets one consistent 413 instead of a generic 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> maxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(413).body(ApiErrorResponse.of("FILE_TOO_LARGE",
                "Uploaded file exceeds the maximum allowed size", List.of(), CorrelationContext.requestId()));
    }

    /**
     * Last-resort handler. Never log-and-swallow: an unhandled exception must
     * appear in the application log with its full stack trace and the request
     * id the client received, otherwise production 500s are undiagnosable.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex) {
        String requestId = CorrelationContext.requestId();
        log.error("unhandled exception requestId={} type={} message={}", requestId, ex.getClass().getName(),
                ex.getMessage(), ex);
        return ResponseEntity.status(500).body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred",
                List.of(), requestId));
    }
}

