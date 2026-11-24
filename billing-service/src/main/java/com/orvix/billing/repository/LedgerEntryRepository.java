package com.orvix.billing.repository;

import com.orvix.billing.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Page<LedgerEntry> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    List<LedgerEntry> findByTransactionId(String transactionId);
}
