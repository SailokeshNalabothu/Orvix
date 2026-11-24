package com.orvix.billing.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "api_key_hash", length = 64, nullable = false, unique = true)
    private String apiKeyHash;

    @Column(name = "rpm_limit", nullable = false)
    private Integer rpmLimit;

    @Column(name = "tpm_limit", nullable = false)
    private Integer tpmLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
