package com.pgms.common.exception;

import com.pgms.common.api.ApiError;
import com.pgms.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception into the {@link ApiError} envelope with a consistent status code.
 * Expected failures log at WARN without stack traces; unexpected ones log at ERROR and never leak internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        log.warn("Request rejected: code={} message={}", ex.getCode(), ex.getMessage());
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage(),
                        isSensitive(fe.getField()) ? null : safeValue(fe.getRejectedValue())))
                .toList();
        List<ApiError.FieldViolation> globalViolations = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiError.FieldViolation(ge.getObjectName(), ge.getDefaultMessage(), null))
                .toList();
        List<ApiError.FieldViolation> all = new ArrayList<>(violations);
        all.addAll(globalViolations);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, all);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> new ApiError.FieldViolation(
                                result.getMethodParameter().getParameterName(), err.getDefaultMessage(),
                                safeValue(result.getArgument()))))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage(), safeValue(v.getInvalidValue())))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, violations);
    }

    /** Spring's own messages name Java types ("for method parameter type Long"); clients get a plain sentence. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Required query parameter '" + ex.getParameterName() + "' is missing", request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        if (IDEMPOTENCY_HEADER.equalsIgnoreCase(ex.getHeaderName())) {
            return build(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required for this request (send a new UUID per operation and "
                            + "reuse it when retrying)", request, List.of());
        }
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Required header '" + ex.getHeaderName() + "' is missing", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = describe(ex.getRequiredType());
        String message = "Invalid value for '" + ex.getName() + "'" + (expected == null ? "" : ": expected " + expected);
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request,
                List.of(new ApiError.FieldViolation(ex.getName(), message, safeValue(ex.getValue()))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint " + request.getRequestURI(), request, List.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleAccessDenied(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action", request,
                List.of());
    }

    /** Two writers raced on the same row (e.g. same bed / invoice); the loser gets a retryable 409. */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConcurrency(RuntimeException ex, HttpServletRequest request) {
        log.warn("Concurrent modification detected: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Please retry.", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        ApiException translated = ConstraintViolationTranslator.translate(ex);
        log.warn("Data integrity violation: constraint={} code={}",
                ConstraintViolationTranslator.constraintName(ex).orElse("unknown"), translated.getCode());
        return build(translated.getStatus(), translated.getCode(), translated.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Reference: " + RequestContext.correlationId(), request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request,
                                           List<ApiError.FieldViolation> fieldErrors) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                request.getRequestURI(), RequestContext.correlationId(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    /** Human description of a parameter type; never a Java class name. */
    private static String describe(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.isEnum()) {
            return "one of " + Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "a number";
        }
        if (type == LocalDate.class) {
            return "a date (yyyy-MM-dd)";
        }
        if (type == Instant.class) {
            return "a timestamp (ISO-8601, e.g. 2024-01-31T10:15:30Z)";
        }
        return null;
    }

    private static boolean isSensitive(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("token") || lower.contains("secret");
    }

    /** Never echo secrets back (e.g. a rejected password field); keep only short scalar values. */
    private static Object safeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = value.toString();
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
