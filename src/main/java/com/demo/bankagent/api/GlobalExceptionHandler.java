package com.demo.bankagent.api;

import com.demo.bankagent.llm.SecretMasker;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to a JSON {@link ApiError} with a stable code and a readable message. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BankingException.class)
    public ResponseEntity<ApiError> banking(BankingException e) {
        log.info("Request rejected code={} message={}", e.getCode(), SecretMasker.mask(e.getMessage()));
        return body(e.getCode().status(), e.getCode().name(), SecretMasker.mask(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Invalid request: " + detail + ".");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Request body is missing or not valid JSON.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not allowed for this endpoint.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unexpected error: {}", SecretMasker.mask(e.toString()));
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "An unexpected error occurred. Check the server log for details.");
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, status.value(), Instant.now()));
    }
}
