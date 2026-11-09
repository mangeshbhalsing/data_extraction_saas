package com.dataextraction.saas.common;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException exception,
                                                                      HttpServletRequest request) {
        return ResponseEntity.status(401).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "code", "INVALID_CREDENTIALS",
                "message", "Email or password is incorrect",
                "path", request.getRequestURI()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", exception.getStatus().value(),
                "code", exception.getCode(),
                "message", exception.getMessage(),
                "path", request.getRequestURI()));
    }
}
