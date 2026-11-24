package com.orvix.gateway.exception;

import com.orvix.common.contract.dto.StandardProblemDetail;
import com.orvix.gateway.auth.AuthAndQuotaService;
import com.orvix.gateway.client.GuardrailClient;
import com.orvix.gateway.controller.GatewayController;
import com.orvix.gateway.ratelimit.RedisRateLimiter;
import com.orvix.gateway.router.LlmRouter;
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

    @ExceptionHandler(AuthAndQuotaService.AuthenticationException.class)
    public ResponseEntity<StandardProblemDetail> handleAuthenticationException(
            AuthAndQuotaService.AuthenticationException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.UNAUTHORIZED, "https://orvix.internal/errors/invalid-api-key",
                "Invalid API Key", ex.getMessage(), request);
    }

    @ExceptionHandler(AuthAndQuotaService.InsufficientQuotaException.class)
    public ResponseEntity<StandardProblemDetail> handleQuotaExhausted(
            AuthAndQuotaService.InsufficientQuotaException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.PAYMENT_REQUIRED, "https://orvix.internal/errors/insufficient-balance",
                "Insufficient Wallet Balance", ex.getMessage(), request);
    }

    @ExceptionHandler(GatewayController.RateLimitExceededException.class)
    public ResponseEntity<StandardProblemDetail> handleRateLimitExceeded(
            GatewayController.RateLimitExceededException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.TOO_MANY_REQUESTS, "https://orvix.internal/errors/rate-limit-exceeded",
                "Rate Limit Exceeded", ex.getMessage(), request);
    }

    @ExceptionHandler(GuardrailClient.PromptInjectionBlockedException.class)
    public ResponseEntity<StandardProblemDetail> handlePromptInjection(
            GuardrailClient.PromptInjectionBlockedException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.BAD_REQUEST, "https://orvix.internal/errors/prompt-injection-blocked",
                "Prompt Injection Blocked", ex.getMessage(), request);
    }

    @ExceptionHandler(GuardrailClient.GuardrailUnavailableException.class)
    public ResponseEntity<StandardProblemDetail> handleGuardrailUnavailable(
            GuardrailClient.GuardrailUnavailableException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "https://orvix.internal/errors/guardrail-unavailable",
                "Guardrail Service Unavailable", ex.getMessage(), request);
    }

    @ExceptionHandler({RedisRateLimiter.RedisUnavailableException.class, AuthAndQuotaService.RedisUnavailableException.class})
    public ResponseEntity<StandardProblemDetail> handleRedisUnavailable(
            RuntimeException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "https://orvix.internal/errors/redis-unavailable",
                "Redis Unavailable", ex.getMessage(), request);
    }

    @ExceptionHandler(LlmRouter.UpstreamLlmException.class)
    public ResponseEntity<StandardProblemDetail> handleUpstreamFailure(
            LlmRouter.UpstreamLlmException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.BAD_GATEWAY, "https://orvix.internal/errors/upstream-llm-failed",
                "Upstream LLM Failed", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request
    ) {
        return buildError(HttpStatus.BAD_REQUEST, "https://orvix.internal/errors/invalid-payload",
                "Invalid Payload", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardProblemDetail> handleGeneric(
            Exception ex, HttpServletRequest request
    ) {
        log.error("Unhandled exception: {}", request.getRequestURI(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "https://orvix.internal/errors/internal-server-error",
                "Internal Server Error", ex.getMessage(), request);
    }

    private ResponseEntity<StandardProblemDetail> buildError(
            HttpStatus status, String type, String title, String detail, HttpServletRequest request
    ) {
        String traceId = request.getHeader("X-Trace-Id") != null ? request.getHeader("X-Trace-Id") : "unknown";
        StandardProblemDetail problem = new StandardProblemDetail(
                type,
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                traceId,
                Instant.now()
        );
        return ResponseEntity.status(status).body(problem);
    }
}
