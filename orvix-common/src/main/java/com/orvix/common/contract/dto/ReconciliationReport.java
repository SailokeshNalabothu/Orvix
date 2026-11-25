package com.orvix.common.contract.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Instant auditTimestamp,
        long totalLedgerRows,
        long sumDebitMicros,
        long sumCreditMicros,
        long ledgerDeltaMicros,
        boolean ledgerBalanced,
        int tenantsAudited,
        int tenantsBalanced,
        int tenantsWithDiscrepancy,
        List<TenantDiscrepancy> discrepancies,
        int orphanedTransactionsCount,
        boolean systemHealthy,
        long outboxPendingCount,
        long outboxFailedCount
) {
    public ReconciliationReport(
            Instant auditTimestamp,
            long totalLedgerRows,
            long sumDebitMicros,
            long sumCreditMicros,
            long ledgerDeltaMicros,
            boolean ledgerBalanced,
            int tenantsAudited,
            int tenantsBalanced,
            int tenantsWithDiscrepancy,
            List<TenantDiscrepancy> discrepancies,
            int orphanedTransactionsCount,
            boolean systemHealthy
    ) {
        this(auditTimestamp, totalLedgerRows, sumDebitMicros, sumCreditMicros, ledgerDeltaMicros,
                ledgerBalanced, tenantsAudited, tenantsBalanced, tenantsWithDiscrepancy,
                discrepancies, orphanedTransactionsCount, systemHealthy, 0L, 0L);
    }

    public record TenantDiscrepancy(
            String tenantId,
            long materializedBalanceMicros,
            long calculatedBalanceMicros,
            long discrepancyMicros
    ) {}
}
