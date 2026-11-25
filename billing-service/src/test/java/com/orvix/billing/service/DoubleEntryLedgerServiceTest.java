package com.orvix.billing.service;

import com.orvix.billing.model.*;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.ProcessedEventRepository;
import com.orvix.billing.repository.RefundIdempotencyRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.CreditWalletResponse;
import com.orvix.common.contract.dto.RefundResponse;
import com.orvix.common.contract.event.QuotaExhaustedEvent;
import com.orvix.common.contract.event.UsageRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoubleEntryLedgerServiceTest {

    @Mock
    private TenantWalletRepository walletRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private RefundIdempotencyRepository refundIdempotencyRepository;

    @Mock
    private CostCalculator costCalculator;

    @Mock
    private OutboxPublisherService outboxPublisherService;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private DoubleEntryLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new DoubleEntryLedgerService(
                walletRepository, ledgerEntryRepository, processedEventRepository,
                refundIdempotencyRepository, costCalculator, outboxPublisherService, redisTemplate
        );
    }

    @Test
    void shouldSkipDuplicateEventIdempotently() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-dup", "trace-1", "ten-1", "req-1", "gemini", "gpt-4o-mini",
                false, 10, 20, Instant.now()
        );
        when(processedEventRepository.existsById("evt-dup")).thenReturn(true);

        ledgerService.recordUsageDebit(event);

        verify(ledgerEntryRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
        verify(outboxPublisherService, never()).stageOutboxEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldHandleConcurrentDuplicateEventGracefully() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-concurrent", "trace-1", "ten-1", "req-1", "gemini", "gpt-4o-mini",
                false, 10, 20, Instant.now()
        );
        when(processedEventRepository.existsById("evt-concurrent")).thenReturn(false);
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation on event_id"));

        // Should return cleanly without throwing or debiting
        assertDoesNotThrow(() -> ledgerService.recordUsageDebit(event));

        verify(ledgerEntryRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void shouldDebitWalletAndCreditRevenueWithBalancedEntries() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-new", "trace-1", "ten-1", "req-1", "gemini", "gpt-4o-mini",
                false, 100, 200, Instant.now()
        );
        when(processedEventRepository.existsById("evt-new")).thenReturn(false);
        when(costCalculator.calculateCostMicros(event)).thenReturn(500L);

        TenantWallet wallet = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(10000L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findById("ten-1")).thenReturn(Optional.of(wallet));

        ledgerService.recordUsageDebit(event);

        // Verify idempotency record saved
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));

        // Verify two balanced entries (DEBIT and CREDIT)
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getAllValues();

        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        assertEquals(500L, debit.getAmountMicros());
        assertEquals(500L, credit.getAmountMicros());
        assertEquals(AccountType.TENANT_WALLET, debit.getAccountType());
        assertEquals(AccountType.PLATFORM_REVENUE, credit.getAccountType());
        assertEquals(debit.getTransactionId(), credit.getTransactionId());

        // Verify wallet balance updated
        assertEquals(9500L, wallet.getBalanceMicros());
        verify(walletRepository).save(wallet);

        // No quota exhaustion event because balance > 0
        verify(outboxPublisherService, never()).stageOutboxEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldStageQuotaExhaustedEventInOutboxWhenBalanceReachesZeroOrNegative() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-exhaust", "trace-1", "ten-1", "req-1", "gemini", "gpt-4o-mini",
                false, 1000, 2000, Instant.now()
        );
        when(processedEventRepository.existsById("evt-exhaust")).thenReturn(false);
        when(costCalculator.calculateCostMicros(event)).thenReturn(1000L);

        TenantWallet wallet = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(500L) // Only 500 balance, cost is 1000
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findById("ten-1")).thenReturn(Optional.of(wallet));

        ledgerService.recordUsageDebit(event);

        assertEquals(-500L, wallet.getBalanceMicros());
        verify(walletRepository).save(wallet);

        // Verify QuotaExhaustedEvent staged in Transactional Outbox
        verify(outboxPublisherService).stageOutboxEvent(
                eq("TENANT_WALLET"),
                eq("ten-1"),
                eq("QUOTA_EXHAUSTED"),
                eq(DoubleEntryLedgerService.TOPIC_QUOTA_EXHAUSTED),
                any(QuotaExhaustedEvent.class)
        );
    }

    @Test
    void shouldCreditWalletWithBalancedDoubleEntry() {
        TenantWallet wallet = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(2000L)
                .version(0L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findById("ten-1")).thenReturn(Optional.of(wallet));

        CreditWalletResponse response = ledgerService.creditWallet("ten-1", 5000L, "PROMO_BONUS");

        assertEquals(2000L, response.previousBalanceMicros());
        assertEquals(5000L, response.creditedAmountMicros());
        assertEquals(7000L, response.newBalanceMicros());

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();

        assertEquals(AccountType.TENANT_WALLET, credit.getAccountType());
        assertEquals(AccountType.FUNDING_SOURCE, debit.getAccountType());
        assertEquals(5000L, credit.getAmountMicros());
        assertEquals(5000L, debit.getAmountMicros());
    }

    @Test
    void shouldProcessRefundWithReversedLedgerAndRestoredWallet() {
        TenantWallet wallet = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(0L) // Quota blocked wallet
                .version(2L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findById("ten-1")).thenReturn(Optional.of(wallet));
        when(refundIdempotencyRepository.findById("ref_idem_tx_orig_123")).thenReturn(Optional.empty());
        when(ledgerEntryRepository.findOriginalDebitAmount("tx_orig_123")).thenReturn(2000L);
        when(ledgerEntryRepository.findCumulativeRefundAmount("tx_orig_123")).thenReturn(0L);

        RefundResponse response = ledgerService.processRefund(
                "ten-1", "tx_orig_123", 1500L, "SERVICE_CREDIT_DISPUTE"
        );

        assertEquals(0L, response.previousBalanceMicros());
        assertEquals(1500L, response.refundedAmountMicros());
        assertEquals(1500L, response.newBalanceMicros());

        // Verify balanced ledger entries: DEBIT platform revenue, CREDIT tenant wallet
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        LedgerEntry debitRev = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry creditWallet = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        assertEquals(AccountType.PLATFORM_REVENUE, debitRev.getAccountType());
        assertEquals(AccountType.TENANT_WALLET, creditWallet.getAccountType());
        assertEquals(1500L, debitRev.getAmountMicros());
        assertEquals(1500L, creditWallet.getAmountMicros());

        // Verify wallet updated
        assertEquals(1500L, wallet.getBalanceMicros());
        verify(walletRepository).save(wallet);

        // Verify RefundIdempotency saved and Outbox event staged
        verify(refundIdempotencyRepository).save(any(RefundIdempotency.class));
        verify(outboxPublisherService).stageOutboxEvent(eq("REFUND"), eq("ten-1"), eq("REFUND_PROCESSED"), anyString(), any());

        // Verify Redis quota block flag cleared because balance > 0
        verify(redisTemplate).delete("quota:blocked:ten-1");
    }

    @Test
    void shouldReturnExistingRefundOnIdempotencyReplayWithoutDoubleRefunding() {
        RefundIdempotency existing = RefundIdempotency.builder()
                .idempotencyKey("idem_ref_999")
                .tenantId("ten-1")
                .referenceTransactionId("tx_orig_123")
                .refundTransactionId("ref_prior_456")
                .amountMicros(1000L)
                .previousBalance(0L)
                .newBalance(1000L)
                .createdAt(Instant.now())
                .build();
        when(refundIdempotencyRepository.findById("idem_ref_999")).thenReturn(Optional.of(existing));

        // Replay refund with same idempotency key
        RefundResponse response = ledgerService.processRefund(
                "ten-1", "tx_orig_123", 1000L, "Duplicate retry", "idem_ref_999"
        );

        // Verifies original response returned
        assertEquals("ref_prior_456", response.refundTransactionId());
        assertEquals(1000L, response.newBalanceMicros());

        // Verifies NO new ledger entries or wallet mutations were executed
        verify(ledgerEntryRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
        verify(outboxPublisherService, never()).stageOutboxEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectOverRefundExceedingOriginalDebit() {
        when(refundIdempotencyRepository.findById(anyString())).thenReturn(Optional.empty());
        when(ledgerEntryRepository.findOriginalDebitAmount("tx_debit_100")).thenReturn(500L);
        when(ledgerEntryRepository.findCumulativeRefundAmount("tx_debit_100")).thenReturn(300L);

        // Original debit was 500, already refunded 300, remaining eligible is 200. Requesting 300 must fail.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ledgerService.processRefund("ten-1", "tx_debit_100", 300L, "Over refund attempt"));

        assertTrue(ex.getMessage().contains("exceeds remaining eligible refund"));
        verify(ledgerEntryRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void shouldRejectInvalidRefundAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.processRefund("ten-1", "tx_1", -100L, "INVALID"));
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.processRefund("ten-1", "tx_1", 0L, "INVALID"));
    }
}
