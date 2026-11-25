package com.orvix.billing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refund_idempotency")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundIdempotency {

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "reference_transaction_id", nullable = false, length = 64)
    private String referenceTransactionId;

    @Column(name = "refund_transaction_id", nullable = false, length = 64)
    private String refundTransactionId;

    @Column(name = "amount_micros", nullable = false)
    private long amountMicros;

    @Column(name = "previous_balance", nullable = false)
    private long previousBalance;

    @Column(name = "new_balance", nullable = false)
    private long newBalance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
