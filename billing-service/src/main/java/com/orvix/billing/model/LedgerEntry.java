package com.orvix.billing.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", length = 36, nullable = false)
    private String transactionId;

    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 24, nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 8, nullable = false)
    private EntryType entryType;

    @Column(name = "amount_micros", nullable = false)
    private Long amountMicros;

    @Column(name = "reference_event_id", length = 64, nullable = false)
    private String referenceEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
