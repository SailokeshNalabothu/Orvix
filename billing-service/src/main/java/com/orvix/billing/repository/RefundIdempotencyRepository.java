package com.orvix.billing.repository;

import com.orvix.billing.model.RefundIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundIdempotencyRepository extends JpaRepository<RefundIdempotency, String> {
    Optional<RefundIdempotency> findByReferenceTransactionId(String referenceTransactionId);
}
