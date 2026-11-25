package com.orvix.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.billing.model.OutboxEvent;
import com.orvix.billing.model.OutboxStatus;
import com.orvix.billing.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates and persists an outbox event within the caller's active database transaction.
     * Guarantees atomic dual-write consistency without network calls inside the business transaction.
     */
    public OutboxEvent stageOutboxEvent(String aggregateType, String aggregateId, String eventType,
                                        String destinationTopic, Object eventPayload) {
        String payloadJson;
        try {
            payloadJson = (eventPayload instanceof String s) ? s : objectMapper.writeValueAsString(eventPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for aggregate: {}", aggregateId, e);
            throw new RuntimeException("Outbox serialization failure", e);
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id("outbox_" + UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .destinationTopic(destinationTopic)
                .payload(payloadJson)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        return outboxEventRepository.save(outboxEvent);
    }

    /**
     * Background worker polling and publishing pending outbox events to Kafka.
     * Guarantees at-least-once delivery with partition key alignment.
     */
    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            publishSingleEvent(event);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSingleEvent(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getDestinationTopic(), event.getAggregateId(), event.getPayload())
                    .get(3, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setErrorMessage(null);
            outboxEventRepository.save(event);
            log.info("Transactional outbox published event: id={}, type={}, topic={}",
                    event.getId(), event.getEventType(), event.getDestinationTopic());
        } catch (Exception ex) {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setErrorMessage(ex.getMessage());
            if (retries >= 10) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event {} permanently failed after {} attempts: {}",
                        event.getId(), retries, ex.getMessage());
            } else {
                log.warn("Outbox publish transient failure for event {} (attempt {}): {}",
                        event.getId(), retries, ex.getMessage());
            }
            outboxEventRepository.save(event);
        }
    }
}
