package com.orvix.gateway.event;

import com.orvix.common.contract.event.QuotaExhaustedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuotaExhaustedListener {

    private final StringRedisTemplate redisTemplate;

    @KafkaListener(
            topics = "orvix.quota.exhausted",
            groupId = "gateway-quota-group"
    )
    public void handleQuotaExhausted(QuotaExhaustedEvent event) {
        log.warn("Received QuotaExhaustedEvent for tenant: {}. Setting Redis quota block flag.", event.tenant_id());
        try {
            redisTemplate.opsForValue().set("quota:blocked:" + event.tenant_id(), "1");
            log.info("Successfully set Redis key quota:blocked:{} = 1", event.tenant_id());
        } catch (Exception ex) {
            log.error("Failed to write quota block flag to Redis for tenant: {}", event.tenant_id(), ex);
        }
    }
}
