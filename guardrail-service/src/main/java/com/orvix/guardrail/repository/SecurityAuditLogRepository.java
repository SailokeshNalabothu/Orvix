package com.orvix.guardrail.repository;

import com.orvix.guardrail.model.SecurityAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {
    Page<SecurityAuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    List<SecurityAuditLog> findByTraceId(String traceId);
}
