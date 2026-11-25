package com.orvix.guardrail.repository;

import com.orvix.guardrail.model.TenantSecurityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantSecurityPolicyRepository extends JpaRepository<TenantSecurityPolicy, String> {
}
