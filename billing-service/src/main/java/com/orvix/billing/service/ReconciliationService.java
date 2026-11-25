package com.orvix.billing.service;

import com.orvix.billing.model.EntryType;
import com.orvix.billing.model.OutboxStatus;
import com.orvix.billing.model.TenantWallet;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.OutboxEventRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.ReconciliationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final TenantWalletRepository walletRepository;
    private final OutboxEventRepository outboxEventRepository;

    private volatile ReconciliationReport lastReport;

    /**
     * Executes complete financial reconciliation audit:
     * Tier 1: Global double-entry balance verification: SUM(DEBIT) == SUM(CREDIT).
     * Tier 2: Per-tenant wallet balance vs. ledger transaction aggregate invariance.
     * Tier 3: Orphaned and unbalanced transaction detection.
     * Tier 4: Transactional Outbox status and stuck event detection.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport runAudit() {
        Instant now = Instant.now();
        log.info("Starting financial reconciliation audit at {}", now);

        // 1. Tier 1: Global Ledger Invariant
        List<Object[]> entrySums = ledgerEntryRepository.sumAmountByEntryType();
        long sumDebit = 0L;
        long sumCredit = 0L;
        for (Object[] row : entrySums) {
            EntryType type = (EntryType) row[0];
            Long total = (Long) row[1];
            if (type == EntryType.DEBIT) {
                sumDebit = total != null ? total : 0L;
            } else if (type == EntryType.CREDIT) {
                sumCredit = total != null ? total : 0L;
            }
        }

        long totalRows = ledgerEntryRepository.count();
        long ledgerDelta = Math.abs(sumDebit - sumCredit);
        boolean ledgerBalanced = (totalRows == 0) || (ledgerDelta == 0);

        // 2. Tier 2: Per-Tenant Wallet vs Ledger Audit
        List<TenantWallet> wallets = walletRepository.findAll();
        int tenantsAudited = wallets.size();
        int tenantsBalanced = 0;
        int tenantsWithDiscrepancy = 0;
        List<ReconciliationReport.TenantDiscrepancy> discrepancies = new ArrayList<>();

        for (TenantWallet wallet : wallets) {
            String tenantId = wallet.getTenantId();
            List<Object[]> walletSums = ledgerEntryRepository.sumWalletEntriesByTenantId(tenantId);
            long totalCreditsToWallet = 0L;
            long totalDebitsFromWallet = 0L;

            for (Object[] row : walletSums) {
                EntryType type = (EntryType) row[0];
                Long total = (Long) row[1];
                if (type == EntryType.CREDIT) {
                    totalCreditsToWallet = total != null ? total : 0L;
                } else if (type == EntryType.DEBIT) {
                    totalDebitsFromWallet = total != null ? total : 0L;
                }
            }

            long calculatedBalance = totalCreditsToWallet - totalDebitsFromWallet;
            long materializedBalance = wallet.getBalanceMicros();
            long discrepancy = Math.abs(materializedBalance - calculatedBalance);

            if (discrepancy == 0) {
                tenantsBalanced++;
            } else {
                tenantsWithDiscrepancy++;
                discrepancies.add(new ReconciliationReport.TenantDiscrepancy(
                        tenantId, materializedBalance, calculatedBalance, discrepancy
                ));
                log.warn("Reconciliation discrepancy detected for tenant {}: materialized={}, calculated={}, delta={}",
                        tenantId, materializedBalance, calculatedBalance, discrepancy);
            }
        }

        // 3. Tier 3: Orphaned Transactions Check
        List<String> orphanedTxIds = ledgerEntryRepository.findOrphanedOrUnbalancedTransactionIds();
        int orphanedCount = orphanedTxIds != null ? orphanedTxIds.size() : 0;
        if (orphanedCount > 0) {
            log.error("Found {} orphaned/unbalanced transactions in ledger: {}", orphanedCount, orphanedTxIds);
        }

        // 4. Tier 4: Outbox Health Check
        long outboxPending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long outboxFailed = outboxEventRepository.countByStatus(OutboxStatus.FAILED);

        boolean systemHealthy = ledgerBalanced && tenantsWithDiscrepancy == 0 && orphanedCount == 0 && outboxFailed == 0;

        ReconciliationReport report = new ReconciliationReport(
                now,
                totalRows,
                sumDebit,
                sumCredit,
                ledgerDelta,
                ledgerBalanced,
                tenantsAudited,
                tenantsBalanced,
                tenantsWithDiscrepancy,
                discrepancies,
                orphanedCount,
                systemHealthy,
                outboxPending,
                outboxFailed
        );

        this.lastReport = report;
        log.info("Reconciliation audit completed: systemHealthy={}, delta={}, discrepancies={}, orphaned={}, outboxPending={}, outboxFailed={}",
                systemHealthy, ledgerDelta, tenantsWithDiscrepancy, orphanedCount, outboxPending, outboxFailed);

        return report;
    }

    public ReconciliationReport getLastReport() {
        if (lastReport == null) {
            return runAudit();
        }
        return lastReport;
    }

    /**
     * Self-heals a tenant wallet balance by restoring it to the exact ledger integral.
     */
    @Transactional
    public long healTenantDiscrepancy(String tenantId) {
        TenantWallet wallet = walletRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for tenant: " + tenantId));
        List<Object[]> walletSums = ledgerEntryRepository.sumWalletEntriesByTenantId(tenantId);
        long totalCredits = 0L;
        long totalDebits = 0L;
        for (Object[] row : walletSums) {
            EntryType type = (EntryType) row[0];
            Long total = (Long) row[1];
            if (type == EntryType.CREDIT) totalCredits = total != null ? total : 0L;
            else if (type == EntryType.DEBIT) totalDebits = total != null ? total : 0L;
        }
        long calculatedBalance = totalCredits - totalDebits;
        wallet.setBalanceMicros(calculatedBalance);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        log.info("Self-healed tenant {} wallet balance to exact ledger integral: {}", tenantId, calculatedBalance);
        return calculatedBalance;
    }
}
