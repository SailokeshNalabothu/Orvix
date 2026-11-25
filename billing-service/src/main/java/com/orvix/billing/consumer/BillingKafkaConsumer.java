package com.orvix.billing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.billing.service.DoubleEntryLedgerService;
import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingKafkaConsumer {

    public static final String TOPIC_USAGE_DLQ = "orvix.usage.dlq";

    private final DoubleEntryLedgerService ledgerService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "orvix.usage.recorded",
            groupId = "billing-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUsageEvent(UsageRecordedEvent event, Acknowledgment ack) {
        log.debug("Received UsageRecordedEvent: id={}, tenant={}", event.event_id(), event.tenant_id());
        try {
            ledgerService.recordUsageDebit(event);
            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception ex) {
            if (isTransientDatabaseError(ex)) {
                log.warn("Transient database connectivity failure for event: id={}. Pausing offset commit to allow recovery: {}",
                        event.event_id(), ex.getMessage());
                throw new RuntimeException("Transient DB failure, backing off for recovery", ex);
            }

            log.error("Permanent failure processing UsageRecordedEvent: id={}. Routing to DLQ topic '{}'",
                    event.event_id(), TOPIC_USAGE_DLQ, ex);
            try {
                String payload;
                try {
                    payload = (objectMapper != null) ? objectMapper.writeValueAsString(event) : event.toString();
                } catch (Exception serEx) {
                    payload = event.toString();
                }
                kafkaTemplate.send(TOPIC_USAGE_DLQ, event.tenant_id(), payload);
                log.info("Successfully routed unprocessable event to DLQ: id={}", event.event_id());
            } catch (Exception dlqEx) {
                log.error("Critical: Failed to publish event to DLQ: id={}", event.event_id(), dlqEx);
            }
            if (ack != null) {
                ack.acknowledge(); // Acknowledge poison pill to prevent head-of-line partition blocking
            }
        }
    }

    public static boolean isTransientDatabaseError(Throwable t) {
        int depth = 0;
        while (t != null && depth < 10) {
            String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
            String cls = t.getClass().getName().toLowerCase();
            if (cls.contains("cannotcreatetransaction") ||
                    cls.contains("transientdataaccess") ||
                    cls.contains("jdbcconnection") ||
                    cls.contains("connectexception") ||
                    cls.contains("socketexception") ||
                    msg.contains("connection refused") ||
                    msg.contains("the connection attempt failed") ||
                    msg.contains("terminating connection") ||
                    msg.contains("could not open connection") ||
                    msg.contains("database system is shutting down")) {
                return true;
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }
}
