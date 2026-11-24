package com.orvix.cache.controller;

import com.orvix.cache.service.SemanticCacheService;
import com.orvix.common.contract.dto.CacheLookupRequest;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.CacheStoreRequest;
import com.orvix.common.contract.dto.CacheStoreResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class CacheController {

    private final SemanticCacheService cacheService;

    @PostMapping("/lookup")
    public ResponseEntity<CacheLookupResponse> lookup(
            @Valid @RequestBody CacheLookupRequest request
    ) {
        CacheLookupResponse response = cacheService.lookup(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/store")
    public ResponseEntity<CacheStoreResponse> store(
            @Valid @RequestBody CacheStoreRequest request
    ) {
        CacheStoreResponse response = cacheService.store(request);
        return ResponseEntity.ok(response);
    }
}
