package com.orvix.billing.service;

import com.orvix.billing.model.*;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.ProcessedEventRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.CreditWalletResponse;
import com.orvix.common.contract.event.QuotaExhaustedEvent;
import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleEntryLedgerService {

    public static final String TOPIC_QUOTA_EXHAUSTED = "orvix.quota.exhausted";

    private final TenantWalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final CostCalculator costCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Atomically processes a usage event:
     * 1. Checks event idempotency via processed_events table.
     * 2. Writes append-only double-entry ledger rows (DEBIT tenant wallet, CREDIT platform revenue).
     * 3. Updates materialized wallet balance with JPA @Version optimistic locking.
     * 4. Automatically retries upon concurrent version conflict (@Retryable max 5 attempts, 20ms jitter).
     * 5. Emits QuotaExhaustedEvent if balance falls to or below zero.
     */
    @Retryable(
            retryFor = {OptimisticLockingFailureException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 20, random = true)
    )
    @Transactional
    public void recordUsageDebit(UsageRecordedEvent event) {
        // 1. Consumer Idempotency Check
        if (processedEventRepository.existsById(event.event_id())) {
            log.info("Duplicate usage event skipped: {}", event.event_id());
            return;
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.event_id())
                .processedAt(Instant.now())
                .build());

        // 2. Cost Calculation (Micro-dollars)
        long costMicros = costCalculator.calculateCostMicros(event);
        String txId = "tx_" + UUID.randomUUID();
        Instant now = Instant.now();

        // 3. Double-Entry Invariant: SUM(DEBIT) == SUM(CREDIT)
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(txId)
                .tenantId(event.tenant_id())
                .accountType(AccountType.TENANT_WALLET)
                .entryType(EntryType.DEBIT)
                .amountMicros(costMicros)
                .referenceEventId(event.event_id())
                .createdAt(now)
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(txId)
                .tenantId(event.tenant_id())
                .accountType(AccountType.PLATFORM_REVENUE)
                .entryType(EntryType.CREDIT)
                .amountMicros(costMicros)
                .referenceEventId(event.event_id())
                .createdAt(now)
                .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 4. Materialized Balance Update
        TenantWallet wallet = walletRepository.findById(event.tenant_id())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for tenant: " + event.tenant_id()));

        long newBalance = wallet.getBalanceMicros() - costMicros;
        wallet.setBalanceMicros(newBalance);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);

        log.info("Deducted {} micro-dollars from tenant: {}, new balance: {} (tx: {})",
                costMicros, event.tenant_id(), newBalance, txId);

        // 5. Quota Exhaustion Check
        if (newBalance <= 0) {
            log.warn("Tenant {} wallet exhausted! Balance: {} micro-dollars. Emitting QuotaExhaustedEvent.",
                    event.tenant_id(), newBalance);

            QuotaExhaustedEvent quotaEvent = new QuotaExhaustedEvent(
                    "qex_" + UUID.randomUUID(),
                    event.tenant_id(),
                    now
            );
            kafkaTemplate.send(TOPIC_QUOTA_EXHAUSTED, event.tenant_id(), quotaEvent);
        }
    }

    /**
     * Atomically credits a tenant wallet with funding:
     * - CREDIT entry to TENANT_WALLET
     * - DEBIT entry to FUNDING_SOURCE
     */
    @Retryable(
            retryFor = {OptimisticLockingFailureException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 20, random = true)
    )
    @Transactional
    public CreditWalletResponse creditWallet(String tenantId, long amountMicros, String reason) {
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("Credit amount must be greater than zero");
        }

        TenantWallet wallet = walletRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for tenant: " + tenantId));

        long previousBalance = wallet.getBalanceMicros();
        long newBalance = previousBalance + amountMicros;
        String txId = "topup_" + UUID.randomUUID();
        Instant now = Instant.now();

        // Double-entry record
        LedgerEntry walletCredit = LedgerEntry.builder()
                .transactionId(txId)
                .tenantId(tenantId)
                .accountType(AccountType.TENANT_WALLET)
                .entryType(EntryType.CREDIT)
                .amountMicros(amountMicros)
                .referenceEventId(reason != null ? reason : "ADMIN_TOPUP")
                .createdAt(now)
                .build();

        LedgerEntry fundingDebit = LedgerEntry.builder()
                .transactionId(txId)
                .tenantId(tenantId)
                .accountType(AccountType.FUNDING_SOURCE)
                .entryType(EntryType.DEBIT)
                .amountMicros(amountMicros)
                .referenceEventId(reason != null ? reason : "ADMIN_TOPUP")
                .createdAt(now)
                .build();

        ledgerEntryRepository.save(walletCredit);
        ledgerEntryRepository.save(fundingDebit);

        wallet.setBalanceMicros(newBalance);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);

        log.info("Credited {} micro-dollars to tenant: {}. Previous: {}, New: {} (tx: {})",
                amountMicros, tenantId, previousBalance, newBalance, txId);

        return new CreditWalletResponse(
                tenantId,
                previousBalance,
                amountMicros,
                newBalance,
                txId,
                now
        );
    }
}
