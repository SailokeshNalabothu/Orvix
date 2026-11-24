package com.orvix.billing.exception;

import com.orvix.common.contract.dto.StandardProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request
    ) {
        String traceId = request.getHeader("X-Trace-Id") != null ? request.getHeader("X-Trace-Id") : "unknown";
        StandardProblemDetail problem = new StandardProblemDetail(
                "https://orvix.internal/errors/invalid-argument",
                "Invalid Argument",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                Instant.now()
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request
    ) {
        String traceId = request.getHeader("X-Trace-Id") != null ? request.getHeader("X-Trace-Id") : "unknown";
        StandardProblemDetail problem = new StandardProblemDetail(
                "https://orvix.internal/errors/invalid-payload",
                "Invalid Payload",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                Instant.now()
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardProblemDetail> handleGenericException(
            Exception ex, HttpServletRequest request
    ) {
        log.error("Unhandled exception processing request: {}", request.getRequestURI(), ex);
        String traceId = request.getHeader("X-Trace-Id") != null ? request.getHeader("X-Trace-Id") : "unknown";
        StandardProblemDetail problem = new StandardProblemDetail(
                "https://orvix.internal/errors/internal-server-error",
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
