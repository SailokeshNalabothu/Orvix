package com.orvix.billing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.billing.service.DoubleEntryLedgerService;
import com.orvix.common.contract.event.UsageRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingKafkaConsumerTest {

    @Mock
    private DoubleEntryLedgerService ledgerService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    private BillingKafkaConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        consumer = new BillingKafkaConsumer(ledgerService, kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("Kafka Consumer: Successfully process usage debit and acknowledge offset")
    void shouldProcessAndAcknowledgeOffset() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt_" + UUID.randomUUID(),
                "trace_123",
                "ten-normal",
                "req_123",
                "openai",
                "gpt-4o",
                false,
                100,
                50,
                Instant.now()
        );

        consumer.consumeUsageEvent(event, acknowledgment);

        verify(ledgerService).recordUsageDebit(event);
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Kafka Consumer: Route poison pill to DLQ and acknowledge offset to unblock partition")
    void shouldRouteToDLQAndAcknowledgeWhenProcessingFails() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt_poison_pill",
                "trace_err",
                "ten-poison",
                "req_err",
                "openai",
                "gpt-4o",
                false,
                100,
                50,
                Instant.now()
        );

        doThrow(new RuntimeException("Database timeout or corrupted payload"))
                .when(ledgerService).recordUsageDebit(any());

        consumer.consumeUsageEvent(event, acknowledgment);

        verify(ledgerService).recordUsageDebit(event);
        verify(kafkaTemplate).send(eq("orvix.usage.dlq"), eq("ten-poison"), any(String.class));
        verify(acknowledgment).acknowledge();
    }
}
