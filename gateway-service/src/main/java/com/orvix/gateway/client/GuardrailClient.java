package com.orvix.gateway.client;

import com.orvix.common.contract.dto.PromptInspectionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
public class GuardrailClient {

    private final RestTemplate restTemplate;
    private final String inspectUrl;

    public GuardrailClient(
            RestTemplateBuilder builder,
            @Value("${orvix.services.guardrail-url:http://localhost:8081}") String guardrailBaseUrl
    ) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(2000))
                .setReadTimeout(Duration.ofMillis(3000))
                .build();
        this.inspectUrl = guardrailBaseUrl + "/internal/v1/inspect";
    }

    public PromptInspectionResponse inspect(String traceId, String tenantId, String prompt) {
        PromptInspectionRequest request = new PromptInspectionRequest(traceId, tenantId, prompt);
        try {
            ResponseEntity<PromptInspectionResponse> response = restTemplate.postForEntity(
                    inspectUrl, request, PromptInspectionResponse.class
            );
            return response.getBody();
        } catch (HttpClientErrorException.BadRequest ex) {
            // Guardrail returned HTTP 400 -> Prompt injection blocked
            log.warn("Guardrail blocked prompt for tenant: {}, traceId: {}", tenantId, traceId);
            throw new PromptInjectionBlockedException("Prompt injection detected. Request rejected.", List.of("PROMPT_INJECTION"));
        } catch (Exception ex) {
            // Fail-Closed security mandate: never forward uninspected prompts
            log.error("Guardrail service unreachable or timed out (>150ms). Fail-Closed policy active.", ex);
            throw new GuardrailUnavailableException("Security Guardrail service is unavailable.", ex);
        }
    }

    public static class PromptInjectionBlockedException extends RuntimeException {
        private final List<String> violations;

        public PromptInjectionBlockedException(String message, List<String> violations) {
            super(message);
            this.violations = violations;
        }

        public List<String> getViolations() {
            return violations;
        }
    }

    public static class GuardrailUnavailableException extends RuntimeException {
        public GuardrailUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
