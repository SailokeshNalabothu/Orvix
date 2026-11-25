package com.orvix.guardrail.service;

import com.orvix.common.contract.dto.PromptInspectionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.guardrail.engine.PiiRedactionEngine;
import com.orvix.guardrail.engine.PromptInjectionDetector;
import com.orvix.guardrail.model.GuardrailAction;
import com.orvix.guardrail.model.SecurityAuditLog;
import com.orvix.guardrail.repository.SecurityAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GuardrailService {

    private final PromptInjectionDetector injectionDetector;
    private final PiiRedactionEngine piiRedactor;
    private final SecurityAuditLogRepository auditLogRepository;

    @Transactional
    public PromptInspectionResponse inspect(PromptInspectionRequest request) {
        log.debug("Inspecting prompt for tenant: {}, traceId: {}", request.tenantId(), request.traceId());

        // 1. Zero-Trust Prompt Injection Defense
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(request.prompt());
        if (detection.isInjection()) {
            String violation = "PROMPT_INJECTION:" + detection.threatVector().name();
            log.warn("Prompt injection detected [vector={}] for tenant: {}, traceId: {}",
                    detection.threatVector(), request.tenantId(), request.traceId());
            auditLogRepository.save(SecurityAuditLog.builder()
                    .traceId(request.traceId())
                    .tenantId(request.tenantId())
                    .action(GuardrailAction.BLOCKED)
                    .violationTypes(List.of("PROMPT_INJECTION", violation))
                    .createdAt(Instant.now())
                    .build());

            return new PromptInspectionResponse(
                    PromptInspectionResponse.STATUS_BLOCKED,
                    null,
                    0,
                    List.of("PROMPT_INJECTION", violation)
            );
        }

        // 2. PII / Secret Redaction
        PiiRedactionEngine.RedactionResult redaction = piiRedactor.redact(request.prompt());
        if (!redaction.violations().isEmpty()) {
            log.info("PII/Secrets redacted for tenant: {}, count: {}, violations: {}",
                    request.tenantId(), redaction.redactionsCount(), redaction.violations());

            auditLogRepository.save(SecurityAuditLog.builder()
                    .traceId(request.traceId())
                    .tenantId(request.tenantId())
                    .action(GuardrailAction.REDACTED)
                    .violationTypes(redaction.violations())
                    .createdAt(Instant.now())
                    .build());

            return new PromptInspectionResponse(
                    PromptInspectionResponse.STATUS_REDACTED,
                    redaction.sanitizedText(),
                    redaction.redactionsCount(),
                    redaction.violations()
            );
        }

        // 3. Clean Prompt
        return new PromptInspectionResponse(
                PromptInspectionResponse.STATUS_CLEAN,
                request.prompt(),
                0,
                List.of()
        );
    }
}
