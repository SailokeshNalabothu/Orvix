package com.orvix.cache.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.cache.service.SemanticCacheService;
import com.orvix.common.contract.dto.CacheLookupRequest;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.CacheStoreRequest;
import com.orvix.common.contract.dto.CacheStoreResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CacheController.class)
class CacheControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SemanticCacheService cacheService;

    @Test
    void shouldReturn200ForLookupHit() throws Exception {
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "Explain Kafka");
        CacheLookupResponse response = CacheLookupResponse.hit("Kafka is a distributed streaming platform", 0.96);
        when(cacheService.lookup(any())).thenReturn(response);

        mockMvc.perform(post("/internal/v1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hit").value(true))
                .andExpect(jsonPath("$.cacheStatus").value("HIT"))
                .andExpect(jsonPath("$.similarity").value(0.96))
                .andExpect(jsonPath("$.cachedResponse").value("Kafka is a distributed streaming platform"));
    }

    @Test
    void shouldReturn200ForLookupMiss() throws Exception {
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "New query");
        when(cacheService.lookup(any())).thenReturn(CacheLookupResponse.miss());

        mockMvc.perform(post("/internal/v1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hit").value(false))
                .andExpect(jsonPath("$.cacheStatus").value("MISS"));
    }

    @Test
    void shouldReturn200ForStore() throws Exception {
        CacheStoreRequest request = new CacheStoreRequest(
                "tenant-1", "gpt-4o-mini", "Query", "Answer", 3600L
        );
        when(cacheService.store(any())).thenReturn(new CacheStoreResponse(true, 123L));

        mockMvc.perform(post("/internal/v1/store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(true))
                .andExpect(jsonPath("$.cacheId").value(123));
    }
}
