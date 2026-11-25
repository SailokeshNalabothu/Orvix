package com.orvix.guardrail.service;

import com.orvix.common.contract.dto.PromptInspectionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.common.contract.dto.TenantSecurityPolicyDto;
import com.orvix.guardrail.engine.PiiRedactionEngine;
import com.orvix.guardrail.engine.PromptInjectionDetector;
import com.orvix.guardrail.model.GuardrailAction;
import com.orvix.guardrail.model.SecurityAuditLog;
import com.orvix.guardrail.model.TenantSecurityPolicy;
import com.orvix.guardrail.repository.SecurityAuditLogRepository;
import com.orvix.guardrail.repository.TenantSecurityPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GuardrailService {

    private final PromptInjectionDetector injectionDetector;
    private final PiiRedactionEngine piiRedactor;
    private final SecurityAuditLogRepository auditLogRepository;
    private final TenantSecurityPolicyRepository policyRepository;

    @Transactional
    public PromptInspectionResponse inspect(PromptInspectionRequest request) {
        log.debug("Inspecting prompt for tenant: {}, traceId: {}", request.tenantId(), request.traceId());

        // 0. Load Tenant-Specific Security Policy (Tenant Security Isolation)
        TenantSecurityPolicy policy = policyRepository.findById(request.tenantId()).orElse(null);

        // 1. Zero-Trust Prompt Injection Defense (with tenant policy context)
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(request.prompt(), policy);
        if (detection.isInjection()) {
            String categoryViolation = "PROMPT_INJECTION:" + detection.threatCategory().name();
            String legacyVectorViolation = "PROMPT_INJECTION:" + detection.threatVector().name();
            log.warn("Prompt injection detected [category={}, vector={}] for tenant: {}, traceId: {}",
                    detection.threatCategory(), detection.threatVector(), request.tenantId(), request.traceId());

            auditLogRepository.save(SecurityAuditLog.builder()
                    .traceId(request.traceId())
                    .tenantId(request.tenantId())
                    .action(GuardrailAction.BLOCKED)
                    .violationTypes(List.of("PROMPT_INJECTION", categoryViolation, legacyVectorViolation))
                    .createdAt(Instant.now())
                    .build());

            return new PromptInspectionResponse(
                    PromptInspectionResponse.STATUS_BLOCKED,
                    null,
                    0,
                    List.of("PROMPT_INJECTION", categoryViolation, legacyVectorViolation)
            );
        }

        // 2. PII / Secret Redaction
        boolean performPiiScrubbing = (policy == null || policy.isStrictPiiScrubbing());
        if (performPiiScrubbing) {
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
        }

        // 3. Clean Prompt
        return new PromptInspectionResponse(
                PromptInspectionResponse.STATUS_CLEAN,
                request.prompt(),
                0,
                List.of()
        );
    }

    @Transactional(readOnly = true)
    public TenantSecurityPolicyDto getTenantPolicy(String tenantId) {
        return policyRepository.findById(tenantId)
                .map(TenantSecurityPolicy::toDto)
                .orElseGet(() -> TenantSecurityPolicyDto.defaultPolicy(tenantId));
    }

    @Transactional
    public TenantSecurityPolicyDto updateTenantPolicy(String tenantId, TenantSecurityPolicyDto dto) {
        TenantSecurityPolicy policy = policyRepository.findById(tenantId)
                .orElse(TenantSecurityPolicy.builder().tenantId(tenantId).build());

        policy.setRiskThreshold(dto.riskThreshold() > 0 ? dto.riskThreshold() : 0.60);
        policy.setStrictMode(dto.strictMode());
        policy.setBlockObfuscation(dto.blockObfuscation());
        policy.setStrictPiiScrubbing(dto.strictPiiScrubbing());
        policy.setCustomBlockedKeywords(dto.customBlockedKeywords() != null ? new ArrayList<>(dto.customBlockedKeywords()) : new ArrayList<>());
        policy.setAllowedKeywords(dto.allowedKeywords() != null ? new ArrayList<>(dto.allowedKeywords()) : new ArrayList<>());
        policy.setUpdatedAt(Instant.now());

        TenantSecurityPolicy saved = policyRepository.save(policy);
        log.info("Updated security policy for tenant: {}, strictMode: {}, threshold: {}",
                tenantId, saved.isStrictMode(), saved.getRiskThreshold());
        return saved.toDto();
    }

    @Transactional(readOnly = true)
    public Page<SecurityAuditLog> getTenantAuditLogs(String tenantId, Pageable pageable) {
        return auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }
}
