package com.bejo.exception;

import com.bejo.dto.response.ApiResponseDto;
import com.google.firebase.messaging.FirebaseMessagingException;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // "METHOD /path" for correlating a client-error log line with the request
    private static String at(HttpServletRequest req) {
        return req.getMethod() + " " + req.getRequestURI();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNoRoute(NoResourceFoundException ex, HttpServletRequest req) {
        log.warn("[404] {} — no matching route", at(req));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDto.error("Route not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.warn("[405] {} — method not allowed", at(req));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponseDto.error("Method not allowed: " + ex.getMethod()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("[400] {} — unreadable body: {}", at(req), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponseDto.error("Request body tidak valid atau tidak ditemukan"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        log.warn("[400] {} — missing header: {}", at(req), ex.getHeaderName());
        return ResponseEntity.badRequest()
                .body(ApiResponseDto.error("Header wajib tidak ada: " + ex.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("[400] {} — bad parameter: {}", at(req), ex.getName());
        return ResponseEntity.badRequest()
                .body(ApiResponseDto.error("Format parameter tidak valid: " + ex.getName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[400] {} — validation failed: {}", at(req), message);
        return ResponseEntity.badRequest().body(ApiResponseDto.error(message));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        String message = ex.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream())
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[400] {} — validation failed: {}", at(req), message);
        return ResponseEntity.badRequest().body(ApiResponseDto.error(message));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] {} — {}", at(req), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDto.error(ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException cve) {
            String constraint = cve.getConstraintName();
            if ("uq_transaction_id".equals(constraint)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponseDto.error("transactionId sudah terdaftar"));
            }
            if ("uq_username".equals(constraint)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponseDto.error("Username sudah digunakan"));
            }
            if ("uq_idempotency_key".equals(constraint)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponseDto.error("Permintaan dengan Idempotency-Key ini sedang diproses, coba lagi sebentar"));
            }
            if ("uq_product_sku".equals(constraint)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponseDto.error("SKU sudah digunakan"));
            }
            if ("uq_category_name".equals(constraint)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponseDto.error("Kategori sudah ada"));
            }
        }
        log.error("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponseDto.error("Data tidak valid atau sudah terdaftar"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("[400] {} — {}", at(req), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponseDto.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Service configuration error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponseDto.error(ex.getMessage()));
    }

    @ExceptionHandler(FirebaseMessagingException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleFcm(FirebaseMessagingException ex) {
        log.error("FCM error [{}]: {}", ex.getMessagingErrorCode(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDto.error("Failed to send notification: " + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(ApiResponseDto.error("Internal server error"));
    }
}
