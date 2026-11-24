package com.orvix.gateway.event;

import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class UsageEventPublisher {

    public static final String TOPIC_USAGE_RECORDED = "orvix.usage.recorded";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes usage metering events asynchronously to Kafka.
     * Fallback Buffer Policy: If Kafka broker is down, logs structured fallback record for reconciliation.
     */
    public void publishUsageAsync(UsageRecordedEvent event) {
        CompletableFuture.runAsync(() -> {
            try {
                kafkaTemplate.send(TOPIC_USAGE_RECORDED, event.tenant_id(), event)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("FALLBACK_BUFFER: Kafka broker unavailable. Writing fallback usage record: {}", event, ex);
                            } else {
                                log.debug("Published usage event ID: {} for tenant: {}", event.event_id(), event.tenant_id());
                            }
                        });
            } catch (Exception ex) {
                log.error("FALLBACK_BUFFER: Failed to enqueue usage event to Kafka: {}", event, ex);
            }
        });
    }
}
