package com.orvix.billing.repository;

import com.orvix.billing.model.EntryType;
import com.orvix.billing.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Page<LedgerEntry> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    List<LedgerEntry> findByTransactionId(String transactionId);
    List<LedgerEntry> findByTenantId(String tenantId);

    @Query("SELECT l.entryType, SUM(l.amountMicros) FROM LedgerEntry l GROUP BY l.entryType")
    List<Object[]> sumAmountByEntryType();

    @Query("SELECT l.entryType, SUM(l.amountMicros) FROM LedgerEntry l WHERE l.tenantId = :tenantId AND l.accountType = com.orvix.billing.model.AccountType.TENANT_WALLET GROUP BY l.entryType")
    List<Object[]> sumWalletEntriesByTenantId(@Param("tenantId") String tenantId);

    @Query("SELECT l.transactionId FROM LedgerEntry l GROUP BY l.transactionId HAVING COUNT(l.id) != 2 OR SUM(CASE WHEN l.entryType = com.orvix.billing.model.EntryType.DEBIT THEN l.amountMicros ELSE -l.amountMicros END) != 0")
    List<String> findOrphanedOrUnbalancedTransactionIds();
}
