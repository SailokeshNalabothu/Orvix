package com.orvix.guardrail.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "security_audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 64, nullable = false)
    private String traceId;

    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false)
    private GuardrailAction action;

    @Convert(converter = StringListConverter.class)
    @Column(name = "violation_types", columnDefinition = "text[]", nullable = false)
    private List<String> violationTypes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
