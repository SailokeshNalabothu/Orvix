package com.orvix.gateway.event;

import com.orvix.common.contract.event.UsageRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private UsageEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new UsageEventPublisher(kafkaTemplate);
    }

    @Test
    @DisplayName("Kafka Publisher: Enqueues to fallback buffer when Kafka broker throws exception")
    void shouldEnqueueToFallbackBufferWhenKafkaBrokerDown() throws InterruptedException {
        when(kafkaTemplate.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Connection to Kafka broker:9092 refused"));

        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt_chaos_1", "trace_1", "ten_test", "req_1", "openai", "gpt-4o", false, 10, 20, Instant.now()
        );

        publisher.publishUsageAsync(event);

        // Allow async CompletableFuture to execute
        Thread.sleep(150);

        assertEquals(1, publisher.getFallbackBufferSize());
    }

    @Test
    @DisplayName("Kafka Publisher: Drains fallback buffer when Kafka recovers")
    void shouldDrainFallbackBufferWhenKafkaRecovers() throws InterruptedException {
        // First simulate broker failure
        when(kafkaTemplate.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Broker down"))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt_chaos_2", "trace_2", "ten_test", "req_2", "openai", "gpt-4o", false, 10, 20, Instant.now()
        );

        publisher.publishUsageAsync(event);
        Thread.sleep(150);
        assertEquals(1, publisher.getFallbackBufferSize());

        // Now trigger drain cycle (Kafka recovered)
        publisher.drainFallbackBuffer();

        // Buffer should be empty
        assertEquals(0, publisher.getFallbackBufferSize());
        verify(kafkaTemplate, times(2)).send(eq("orvix.usage.recorded"), eq("ten_test"), eq(event));
    }
}
