package com.orvix.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.billing.model.OutboxEvent;
import com.orvix.billing.model.OutboxStatus;
import com.orvix.billing.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        publisherService = new OutboxPublisherService(outboxEventRepository, kafkaTemplate, objectMapper);
    }

    @Test
    void shouldStageOutboxEventAsPending() {
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        OutboxEvent staged = publisherService.stageOutboxEvent(
                "TENANT_WALLET", "ten_test", "QUOTA_EXHAUSTED", "orvix.quota.exhausted", "{\"reason\":\"exhausted\"}"
        );

        assertNotNull(staged.getId());
        assertEquals("TENANT_WALLET", staged.getAggregateType());
        assertEquals("ten_test", staged.getAggregateId());
        assertEquals("QUOTA_EXHAUSTED", staged.getEventType());
        assertEquals(OutboxStatus.PENDING, staged.getStatus());
        assertEquals(0, staged.getRetryCount());
        assertNotNull(staged.getCreatedAt());
        assertNull(staged.getPublishedAt());
    }

    @Test
    void shouldPublishPendingEventAndMarkAsPublished() {
        OutboxEvent event = OutboxEvent.builder()
                .id("outbox_123")
                .aggregateType("TENANT_WALLET")
                .aggregateId("ten_1")
                .eventType("QUOTA_EXHAUSTED")
                .destinationTopic("orvix.quota.exhausted")
                .payload("{\"test\":1}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(event));

        SendResult<String, Object> mockResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(mockResult);
        when(kafkaTemplate.send(eq("orvix.quota.exhausted"), eq("ten_1"), eq("{\"test\":1}")))
                .thenReturn(future);

        publisherService.publishPendingEvents();

        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getErrorMessage());
        verify(outboxEventRepository).save(event);
    }

    @Test
    void shouldIncrementRetryCountOnKafkaFailure() {
        OutboxEvent event = OutboxEvent.builder()
                .id("outbox_err")
                .aggregateType("TENANT_WALLET")
                .aggregateId("ten_1")
                .eventType("QUOTA_EXHAUSTED")
                .destinationTopic("orvix.quota.exhausted")
                .payload("{\"test\":1}")
                .status(OutboxStatus.PENDING)
                .retryCount(2)
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(failedFuture);

        publisherService.publishPendingEvents();

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(3, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        verify(outboxEventRepository).save(event);
    }
}
