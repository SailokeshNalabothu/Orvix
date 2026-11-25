package com.orvix.gateway.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.common.contract.dto.ChatCompletionResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DurableIdempotencyStore (ADR-016)
 * Durable Tier-2 (L2) Persistent Idempotency Storage.
 *
 * Prevents loss of client request idempotency and duplicate LLM billing
 * during Redis cache eviction, memory pressure, or Redis broker restarts.
 */
@Component
@Slf4j
public class DurableIdempotencyStore {

    private final Path idempotencyDir;
    private final Path walFile;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    // In-memory index of tenant:idempotencyKey -> serialized response
    private final Map<String, String> durableIndex = Collections.synchronizedMap(new LinkedHashMap<>());

    public DurableIdempotencyStore(
            ObjectMapper objectMapper,
            @Value("${orvix.idempotency.dir:data/idempotency}") String idempotencyDirPath) {
        this.objectMapper = objectMapper;
        this.idempotencyDir = Paths.get(idempotencyDirPath);
        this.walFile = this.idempotencyDir.resolve("gateway_idempotency_records.wal");
    }

    @PostConstruct
    public void init() {
        lock.lock();
        try {
            if (!Files.exists(idempotencyDir)) {
                Files.createDirectories(idempotencyDir);
                log.info("Initialized durable idempotency store directory at: {}", idempotencyDir.toAbsolutePath());
            }
            recoverFromDisk();
        } catch (IOException e) {
            log.error("Failed to initialize durable idempotency directory: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves completed response from persistent L2 disk store.
     */
    public ChatCompletionResponse get(String tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null) return null;
        String compoundKey = tenantId + ":" + idempotencyKey;

        String json = durableIndex.get(compoundKey);
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, ChatCompletionResponse.class);
            } catch (Exception ex) {
                log.error("Failed to deserialize durable idempotency payload for {}: {}", compoundKey, ex.getMessage());
            }
        }
        return null;
    }

    /**
     * Persists completed response to persistent L2 disk store with immediate flush.
     */
    public void put(String tenantId, String idempotencyKey, ChatCompletionResponse response) {
        if (tenantId == null || idempotencyKey == null || response == null) return;
        String compoundKey = tenantId + ":" + idempotencyKey;

        lock.lock();
        try {
            String json = objectMapper.writeValueAsString(response);
            durableIndex.put(compoundKey, json);
            appendToDisk(compoundKey, json);
            log.debug("DURABLE_IDEMPOTENCY: Persisted L2 idempotency record to disk WAL: {}", compoundKey);
        } catch (Exception ex) {
            log.error("Failed to persist durable idempotency record {}: {}", compoundKey, ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return durableIndex.size();
    }

    private void appendToDisk(String compoundKey, String json) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(walFile.toFile(), true))) {
            writer.write(compoundKey + "|" + json);
            writer.newLine();
            writer.flush();
        }
    }

    private void recoverFromDisk() {
        File file = walFile.toFile();
        if (!file.exists() || file.length() == 0) {
            return;
        }

        log.info("DURABLE_IDEMPOTENCY: Recovering idempotency records from disk WAL: {}", walFile);
        int recovered = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int pipeIndex = line.indexOf('|');
                if (pipeIndex > 0) {
                    String compoundKey = line.substring(0, pipeIndex);
                    String json = line.substring(pipeIndex + 1);
                    durableIndex.put(compoundKey, json);
                    recovered++;
                }
            }
        } catch (IOException e) {
            log.error("Error reading idempotency WAL file {}: {}", walFile, e.getMessage());
        }

        if (recovered > 0) {
            log.info("DURABLE_IDEMPOTENCY: Restored {} idempotency replay keys from persistent disk store!", recovered);
        }
    }
}
