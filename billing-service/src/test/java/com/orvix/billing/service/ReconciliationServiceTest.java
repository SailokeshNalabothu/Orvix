package com.orvix.billing.service;

import com.orvix.billing.model.EntryType;
import com.orvix.billing.model.TenantWallet;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.ReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private TenantWalletRepository walletRepository;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(ledgerEntryRepository, walletRepository);
    }

    @Test
    @DisplayName("Audit: Global ledger balanced, wallets match transactions -> System Healthy")
    void shouldReportSystemHealthyWhenLedgerAndWalletsAreBalanced() {
        // Global sums: 5,000,000 DEBIT, 5,000,000 CREDIT
        List<Object[]> globalSums = List.of(
                new Object[]{EntryType.DEBIT, 5000000L},
                new Object[]{EntryType.CREDIT, 5000000L}
        );
        when(ledgerEntryRepository.sumAmountByEntryType()).thenReturn(globalSums);
        when(ledgerEntryRepository.count()).thenReturn(100L);

        // Tenant 1 wallet
        TenantWallet wallet1 = TenantWallet.builder()
                .tenantId("ten-1")
                .balanceMicros(3500L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findAll()).thenReturn(List.of(wallet1));

        // Tenant 1 entries: CREDIT 5000 (topup), DEBIT 1500 (usage) -> 5000 - 1500 = 3500
        List<Object[]> tenant1Sums = List.of(
                new Object[]{EntryType.CREDIT, 5000L},
                new Object[]{EntryType.DEBIT, 1500L}
        );
        when(ledgerEntryRepository.sumWalletEntriesByTenantId("ten-1")).thenReturn(tenant1Sums);

        // No orphaned transactions
        when(ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds()).thenReturn(List.of());

        ReconciliationReport report = reconciliationService.runAudit();

        assertTrue(report.systemHealthy());
        assertTrue(report.ledgerBalanced());
        assertEquals(0L, report.ledgerDeltaMicros());
        assertEquals(1, report.tenantsAudited());
        assertEquals(1, report.tenantsBalanced());
        assertEquals(0, report.tenantsWithDiscrepancy());
        assertEquals(0, report.orphanedTransactionsCount());
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

        // Materialized balance is corrupted to 9999L
        TenantWallet corruptedWallet = TenantWallet.builder()
                .tenantId("ten-anomaly")
                .balanceMicros(9999L)
                .version(1L)
                .updatedAt(Instant.now())
                .build();
        when(walletRepository.findAll()).thenReturn(List.of(corruptedWallet));

        // Ledger says: credits=1000, debits=0 -> calculated balance should be 1000L
        List<Object[]> tenantSums = List.<Object[]>of(
                new Object[]{EntryType.CREDIT, 1000L}
        );
        when(ledgerEntryRepository.sumWalletEntriesByTenantId("ten-anomaly")).thenReturn(tenantSums);
        when(ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds()).thenReturn(List.of());

        ReconciliationReport report = reconciliationService.runAudit();

        assertFalse(report.systemHealthy());
        assertEquals(1, report.tenantsWithDiscrepancy());
        assertEquals(8999L, report.discrepancies().get(0).discrepancyMicros());
        assertEquals("ten-anomaly", report.discrepancies().get(0).tenantId());
    }

    @Test
    @DisplayName("Audit: Detect orphaned/unbalanced transactions")
    void shouldDetectOrphanedTransactions() {
        when(ledgerEntryRepository.sumAmountByEntryType()).thenReturn(List.of(
                new Object[]{EntryType.DEBIT, 1000L},
                new Object[]{EntryType.CREDIT, 800L} // Imbalance
        ));
        when(ledgerEntryRepository.count()).thenReturn(3L);
        when(walletRepository.findAll()).thenReturn(List.of());
        when(ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds())
                .thenReturn(List.of("tx_broken_123"));

        ReconciliationReport report = reconciliationService.runAudit();

        assertFalse(report.systemHealthy());
        assertFalse(report.ledgerBalanced());
        assertEquals(200L, report.ledgerDeltaMicros());
        assertEquals(1, report.orphanedTransactionsCount());
    }
}
