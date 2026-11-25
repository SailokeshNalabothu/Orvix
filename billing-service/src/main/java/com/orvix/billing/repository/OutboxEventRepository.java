package com.orvix.billing.repository;

import com.orvix.billing.model.OutboxEvent;
import com.orvix.billing.model.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
    long countByStatus(OutboxStatus status);
}
