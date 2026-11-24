package com.orvix.billing.service;

import com.orvix.billing.model.*;
import com.orvix.billing.repository.LedgerEntryRepository;
import com.orvix.billing.repository.TenantRepository;
import com.orvix.billing.repository.TenantWalletRepository;
import com.orvix.common.contract.dto.CreateTenantRequest;
import com.orvix.common.contract.dto.CreateTenantResponse;
import com.orvix.common.contract.dto.LedgerEntryDto;
import com.orvix.common.contract.dto.LedgerPageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAdminService {

    private final TenantRepository tenantRepository;
    private final TenantWalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final DoubleEntryLedgerService doubleEntryLedgerService;

    @Transactional
    public CreateTenantResponse createTenant(CreateTenantRequest request) {
        String tenantId = "ten_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String rawApiKey = "orv_live_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String apiKeyHash = sha256Hex(rawApiKey);
        Instant now = Instant.now();

        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .name(request.name())
                .apiKeyHash(apiKeyHash)
                .rpmLimit(request.rpmLimit())
                .tpmLimit(request.tpmLimit())
                .status(TenantStatus.ACTIVE)
                .createdAt(now)
                .build();

        tenantRepository.save(tenant);

        TenantWallet wallet = TenantWallet.builder()
                .tenantId(tenantId)
                .balanceMicros(0L)
                .version(0L)
                .updatedAt(now)
                .build();

        walletRepository.save(wallet);

        long initialBalance = request.getEffectiveInitialBalance();
        if (initialBalance > 0) {
            doubleEntryLedgerService.creditWallet(tenantId, initialBalance, "INITIAL_PROVISIONING");
        }

        log.info("Created tenant: {} (id: {}) with RPM: {}, TPM: {}, InitialBalance: {}",
                tenant.getName(), tenantId, tenant.getRpmLimit(), tenant.getTpmLimit(), initialBalance);

        return new CreateTenantResponse(
                tenantId,
                tenant.getName(),
                rawApiKey,
                tenant.getRpmLimit(),
                tenant.getTpmLimit(),
                tenant.getStatus().name(),
                initialBalance,
                now
        );
    }

    @Transactional(readOnly = true)
    public LedgerPageResponse getLedger(String tenantId, int page, int size) {
        TenantWallet wallet = walletRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<LedgerEntry> ledgerPage = ledgerEntryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageRequest);

        List<LedgerEntryDto> dtos = ledgerPage.getContent().stream()
                .map(entry -> new LedgerEntryDto(
                        entry.getId(),
                        entry.getTransactionId(),
                        entry.getTenantId(),
                        entry.getAccountType().name(),
                        entry.getEntryType().name(),
                        entry.getAmountMicros(),
                        entry.getReferenceEventId(),
                        entry.getCreatedAt()
                ))
                .toList();

        return new LedgerPageResponse(
                tenantId,
                wallet.getBalanceMicros(),
                dtos,
                page,
                size,
                ledgerPage.getTotalElements()
        );
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
