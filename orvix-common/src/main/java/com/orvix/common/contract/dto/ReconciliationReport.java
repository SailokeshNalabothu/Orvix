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
        boolean systemHealthy
) {
    public record TenantDiscrepancy(
            String tenantId,
            long materializedBalanceMicros,
            long calculatedBalanceMicros,
            long discrepancyMicros
    ) {}
}
