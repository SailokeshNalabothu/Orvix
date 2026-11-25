package com.orvix.gateway.event;

import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@RequiredArgsConstructor
@Slf4j
public class UsageEventPublisher {

    public static final String TOPIC_USAGE_RECORDED = "orvix.usage.recorded";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ConcurrentLinkedQueue<UsageRecordedEvent> fallbackBuffer = new ConcurrentLinkedQueue<>();

    /**
     * Publishes usage metering events asynchronously to Kafka.
     * Fallback Buffer Policy: If Kafka broker is down or unreachable, enqueues to fallbackBuffer
     * so zero transactions are lost during broker outages.
     */
    public void publishUsageAsync(UsageRecordedEvent event) {
        CompletableFuture.runAsync(() -> {
            try {
                kafkaTemplate.send(TOPIC_USAGE_RECORDED, event.tenant_id(), event)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.warn("FALLBACK_BUFFER: Kafka broker unavailable. Enqueueing to resilient memory buffer: id={}, tenant={}",
                                        event.event_id(), event.tenant_id());
                                fallbackBuffer.offer(event);
                            } else {
                                log.debug("Published usage event ID: {} for tenant: {}", event.event_id(), event.tenant_id());
                            }
                        });
            } catch (Exception ex) {
                log.warn("FALLBACK_BUFFER: Failed to enqueue to Kafka synchronously. Enqueueing to resilient memory buffer: id={}, tenant={}",
                        event.event_id(), event.tenant_id());
                fallbackBuffer.offer(event);
            }
        });
    }

    /**
     * Periodically drains the fallback buffer as soon as Kafka connectivity is restored.
     */
    @Scheduled(fixedDelay = 2000)
    public void drainFallbackBuffer() {
        if (fallbackBuffer.isEmpty()) {
            return;
        }

        int bufferSize = fallbackBuffer.size();
        log.info("Attempting to drain resilient fallback buffer (current size: {} events)...", bufferSize);

        UsageRecordedEvent event;
        int drained = 0;
        while ((event = fallbackBuffer.poll()) != null) {
            final UsageRecordedEvent currentEvent = event;
            try {
                kafkaTemplate.send(TOPIC_USAGE_RECORDED, currentEvent.tenant_id(), currentEvent)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Kafka still unavailable during buffer drain: id={}. Re-queueing.", currentEvent.event_id());
                                fallbackBuffer.offer(currentEvent);
                            } else {
                                log.info("Successfully recovered & flushed buffered event: id={}", currentEvent.event_id());
                            }
                        });
                drained++;
            } catch (Exception ex) {
                log.error("Immediate failure during buffer drain: id={}. Re-queueing.", currentEvent.event_id());
                fallbackBuffer.offer(currentEvent);
                break; // Stop draining this cycle if Kafka is still unreachable
            }
        }
        if (drained > 0) {
            log.info("Dispatched {} buffered events to Kafka for recovery", drained);
        }
    }

    public int getFallbackBufferSize() {
        return fallbackBuffer.size();
    }
}
