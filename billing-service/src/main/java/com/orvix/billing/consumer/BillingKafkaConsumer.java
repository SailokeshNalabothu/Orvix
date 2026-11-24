package com.orvix.billing.consumer;

import com.orvix.billing.service.DoubleEntryLedgerService;
import com.orvix.common.contract.event.UsageRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingKafkaConsumer {

    private final DoubleEntryLedgerService ledgerService;

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
            log.error("Failed to process UsageRecordedEvent: id={}", event.event_id(), ex);
            throw ex; // Triggers Spring Kafka retry and DLQ routing
        }
    }
}
