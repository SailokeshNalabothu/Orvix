package com.orvix.gateway.event;

import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class UsageEventPublisher {

    public static final String TOPIC_USAGE_RECORDED = "orvix.usage.recorded";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DurableSpillwayStore spillwayStore;

    /**
     * Publishes usage metering events asynchronously to Kafka.
     * Durable Spillway Policy (ADR-016): If Kafka broker is down or unreachable,
     * immediately appends to durable disk Write-Ahead Log (WAL) so zero events are lost across crashes or restarts.
     */
    public void publishUsageAsync(UsageRecordedEvent event) {
        CompletableFuture.runAsync(() -> {
            try {
                kafkaTemplate.send(TOPIC_USAGE_RECORDED, event.tenant_id(), event)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.warn("DURABLE_SPILLWAY: Kafka broker unavailable. Enqueueing to disk WAL: id={}, tenant={}",
                                        event.event_id(), event.tenant_id());
                                spillwayStore.persistPendingEvent(event);
                            } else {
                                log.debug("Published usage event ID: {} for tenant: {}", event.event_id(), event.tenant_id());
                            }
                        });
            } catch (Exception ex) {
                log.warn("DURABLE_SPILLWAY: Failed to enqueue to Kafka synchronously. Enqueueing to disk WAL: id={}, tenant={}",
                        event.event_id(), event.tenant_id());
                spillwayStore.persistPendingEvent(event);
            }
        });
    }

    /**
     * Periodically drains the durable disk spillway buffer as soon as Kafka connectivity is restored.
     */
    @Scheduled(fixedDelay = 2000)
    public void drainFallbackBuffer() {
        List<UsageRecordedEvent> pending = spillwayStore.getPendingEvents();
        if (pending == null || pending.isEmpty()) {
            return;
        }

        int bufferSize = pending.size();
        log.info("Attempting to drain durable spillway WAL buffer (current size: {} events)...", bufferSize);

        for (UsageRecordedEvent currentEvent : pending) {
            try {
                kafkaTemplate.send(TOPIC_USAGE_RECORDED, currentEvent.tenant_id(), currentEvent)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Kafka still unavailable during spillway drain: id={}.", currentEvent.event_id());
                            } else {
                                log.info("Successfully recovered & flushed spillway event: id={}", currentEvent.event_id());
                                spillwayStore.markDelivered(currentEvent.event_id());
                            }
                        });
            } catch (Exception ex) {
                log.error("Immediate failure during spillway drain: id={}.", currentEvent.event_id());
                break; // Stop draining this cycle if Kafka is still unreachable
            }
        }
    }

    public int getFallbackBufferSize() {
        return spillwayStore.getSpillwayCount();
    }
}
