package com.orvix.billing.service;

import com.orvix.billing.model.*;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.ProcessedEventRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.CreditWalletResponse;
import com.orvix.common.contract.event.QuotaExhaustedEvent;
import com.orvix.common.contract.event.UsageRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

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
    private CostCalculator costCalculator;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private DoubleEntryLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new DoubleEntryLedgerService(
                walletRepository, ledgerEntryRepository, processedEventRepository, costCalculator, kafkaTemplate, redisTemplate
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
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
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
        verify(processedEventRepository).save(any(ProcessedEvent.class));

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
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void shouldEmitQuotaExhaustedEventWhenBalanceReachesZeroOrNegative() {
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

        // Verify QuotaExhaustedEvent published
        verify(kafkaTemplate).send(eq(DoubleEntryLedgerService.TOPIC_QUOTA_EXHAUSTED), eq("ten-1"), any(QuotaExhaustedEvent.class));
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

        com.orvix.common.contract.dto.RefundResponse response = ledgerService.processRefund(
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

        // Verify Redis quota block flag cleared because balance > 0
        verify(redisTemplate).delete("quota:blocked:ten-1");
    }

    @Test
    void shouldRejectInvalidRefundAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.processRefund("ten-1", "tx_1", -100L, "INVALID"));
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.processRefund("ten-1", "tx_1", 0L, "INVALID"));
    }
}
