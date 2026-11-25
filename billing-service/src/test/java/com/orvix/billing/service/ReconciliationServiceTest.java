package com.orvix.billing.service;

import com.orvix.billing.model.EntryType;
import com.orvix.billing.model.OutboxStatus;
import com.orvix.billing.model.TenantWallet;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.OutboxEventRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.ReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private TenantWalletRepository walletRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(ledgerEntryRepository, walletRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("Audit: Global ledger balanced, wallets match transactions -> System Healthy")
    void shouldReportSystemHealthyWhenLedgerAndWalletsAreBalanced() {
        List<Object[]> globalSums = List.of(
                new Object[]{EntryType.DEBIT, 5000000L},
                new Object[]{EntryType.CREDIT, 5000000L}
        );
        when(ledgerEntryRepository.sumAmountByEntryType()).thenReturn(globalSums);
        when(ledgerEntryRepository.count()).thenReturn(100L);

        TenantWallet wallet1 = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(3500L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findAll()).thenReturn(List.of(wallet1));

        List<Object[]> tenant1Sums = List.of(
                new Object[]{EntryType.CREDIT, 5000L},
                new Object[]{EntryType.DEBIT, 1500L}
        );
        when(ledgerEntryRepository.sumWalletEntriesByTenantId("ten-1")).thenReturn(tenant1Sums);
        when(ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds()).thenReturn(List.of());
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(0L);

        ReconciliationReport report = reconciliationService.runAudit();

        assertTrue(report.systemHealthy());
        assertTrue(report.ledgerBalanced());
        assertEquals(0L, report.ledgerDeltaMicros());
        assertEquals(1, report.tenantsAudited());
        assertEquals(1, report.tenantsBalanced());
        assertEquals(0, report.tenantsWithDiscrepancy());
        assertEquals(0, report.orphanedTransactionsCount());
        assertEquals(0L, report.outboxPendingCount());
        assertEquals(0L, report.outboxFailedCount());
        assertTrue(report.discrepancies().isEmpty());
    }

    @Test
    @DisplayName("Audit: Discrepancy detected when tenant materialized balance differs from ledger")
    void shouldDetectDiscrepancyWhenTenantWalletDoesNotMatchLedger() {
        List<Object[]> globalSums = List.of(
                new Object[]{EntryType.DEBIT, 1000L},
                new Object[]{EntryType.CREDIT, 1000L}
        );
        when(ledgerEntryRepository.sumAmountByEntryType()).thenReturn(globalSums);
        when(ledgerEntryRepository.count()).thenReturn(2L);

        TenantWallet corruptedWallet = TenantWallet.builder()
                .tenantId("ten-anomaly")
                .balanceMicros(9999L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findAll()).thenReturn(List.of(corruptedWallet));

        List<Object[]> tenantSums = List.<Object[]>of(
                new Object[]{EntryType.CREDIT, 1000L}
        );
        when(ledgerEntryRepository.sumWalletEntriesByTenantId("ten-anomaly")).thenReturn(tenantSums);
        when(ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds()).thenReturn(List.of());
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(0L);

        ReconciliationReport report = reconciliationService.runAudit();

        assertFalse(report.systemHealthy());
        assertEquals(1, report.tenantsWithDiscrepancy());
        assertEquals(1, report.discrepancies().size());

        ReconciliationReport.TenantDiscrepancy discrepancy = report.discrepancies().get(0);
        assertEquals("ten-anomaly", discrepancy.tenantId());
        assertEquals(9999L, discrepancy.materializedBalanceMicros());
        assertEquals(1000L, discrepancy.calculatedBalanceMicros());
        assertEquals(8999L, discrepancy.discrepancyMicros());
    }

    @Test
    @DisplayName("Self-Healing: Restores tenant wallet balance to match ledger integral")
    void shouldHealTenantDiscrepancyToMatchLedger() {
        TenantWallet corruptedWallet = TenantWallet.builder()
                .tenantId("ten-heal")
                .balanceMicros(9999L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findById("ten-heal")).thenReturn(Optional.of(corruptedWallet));

        List<Object[]> tenantSums = List.of(
                new Object[]{EntryType.CREDIT, 5000L},
                new Object[]{EntryType.DEBIT, 2000L}
        );
        when(ledgerEntryRepository.sumWalletEntriesByTenantId("ten-heal")).thenReturn(tenantSums);

        long healedBalance = reconciliationService.healTenantDiscrepancy("ten-heal");

        assertEquals(3000L, healedBalance);
        assertEquals(3000L, corruptedWallet.getBalanceMicros());
        verify(walletRepository).save(corruptedWallet);
    }
}
