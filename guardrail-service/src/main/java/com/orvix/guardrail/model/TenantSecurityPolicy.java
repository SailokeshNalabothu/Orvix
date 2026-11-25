package com.orvix.guardrail.model;

import com.orvix.common.contract.dto.TenantSecurityPolicyDto;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tenant_security_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSecurityPolicy {

    @Id
    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Column(name = "risk_threshold", nullable = false)
    @Builder.Default
    private double riskThreshold = 0.60;

    @Column(name = "strict_mode", nullable = false)
    @Builder.Default
    private boolean strictMode = false;

    @Column(name = "block_obfuscation", nullable = false)
    @Builder.Default
    private boolean blockObfuscation = true;

    @Column(name = "strict_pii_scrubbing", nullable = false)
    @Builder.Default
    private boolean strictPiiScrubbing = true;

    @org.hibernate.annotations.ColumnTransformer(write = "?::text[]")
    @Convert(converter = StringListConverter.class)
    @Column(name = "custom_blocked_keywords", columnDefinition = "text[]")
    @Builder.Default
    private List<String> customBlockedKeywords = new ArrayList<>();

    @org.hibernate.annotations.ColumnTransformer(write = "?::text[]")
    @Convert(converter = StringListConverter.class)
    @Column(name = "allowed_keywords", columnDefinition = "text[]")
    @Builder.Default
    private List<String> allowedKeywords = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TenantSecurityPolicyDto toDto() {
        return new TenantSecurityPolicyDto(
                tenantId,
                riskThreshold,
                strictMode,
                blockObfuscation,
                strictPiiScrubbing,
                customBlockedKeywords != null ? customBlockedKeywords : List.of(),
                allowedKeywords != null ? allowedKeywords : List.of(),
                updatedAt
        );
    }

    public static TenantSecurityPolicy fromDto(TenantSecurityPolicyDto dto) {
        return TenantSecurityPolicy.builder()
                .tenantId(dto.tenantId())
                .riskThreshold(dto.riskThreshold())
                .strictMode(dto.strictMode())
                .blockObfuscation(dto.blockObfuscation())
                .strictPiiScrubbing(dto.strictPiiScrubbing())
                .customBlockedKeywords(dto.customBlockedKeywords() != null ? new ArrayList<>(dto.customBlockedKeywords()) : new ArrayList<>())
                .allowedKeywords(dto.allowedKeywords() != null ? new ArrayList<>(dto.allowedKeywords()) : new ArrayList<>())
                .updatedAt(dto.updatedAt() != null ? dto.updatedAt() : Instant.now())
                .build();
    }
}
