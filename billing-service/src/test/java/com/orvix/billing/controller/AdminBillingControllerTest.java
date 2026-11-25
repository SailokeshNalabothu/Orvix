package com.orvix.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.billing.service.DoubleEntryLedgerService;
import com.orvix.billing.service.ReconciliationService;
import com.orvix.billing.service.TenantAdminService;
import com.orvix.common.contract.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminBillingController.class)
class AdminBillingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantAdminService adminService;

    @MockBean
    private DoubleEntryLedgerService ledgerService;

    @MockBean
    private ReconciliationService reconciliationService;

    @Test
    void shouldCreateTenantSuccessfully() throws Exception {
        CreateTenantRequest request = new CreateTenantRequest("Acme Corp", 60, 10000, 5000000L);
        CreateTenantResponse response = new CreateTenantResponse(
                "ten_12345", "Acme Corp", "orv_live_secretkey", 60, 10000, "ACTIVE", 5000000L, Instant.now()
        );
        when(adminService.createTenant(any())).thenReturn(response);

        mockMvc.perform(post("/admin/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("ten_12345"))
                .andExpect(jsonPath("$.name").value("Acme Corp"))
                .andExpect(jsonPath("$.rawApiKey").value("orv_live_secretkey"))
                .andExpect(jsonPath("$.balanceMicros").value(5000000L));
    }

    @Test
    void shouldCreditWalletSuccessfully() throws Exception {
        CreditWalletRequest request = new CreditWalletRequest(2000000L, "STRIPE_TOPUP");
        CreditWalletResponse response = new CreditWalletResponse(
                "ten_12345", 1000000L, 2000000L, 3000000L, "topup_tx_1", Instant.now()
        );
        when(ledgerService.creditWallet(eq("ten_12345"), eq(2000000L), eq("STRIPE_TOPUP"))).thenReturn(response);

        mockMvc.perform(post("/admin/v1/tenants/ten_12345/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("ten_12345"))
                .andExpect(jsonPath("$.previousBalanceMicros").value(1000000L))
                .andExpect(jsonPath("$.creditedAmountMicros").value(2000000L))
                .andExpect(jsonPath("$.newBalanceMicros").value(3000000L));
    }

    @Test
    void shouldGetLedgerSuccessfully() throws Exception {
        LedgerEntryDto entry = new LedgerEntryDto(
                1L, "tx_1", "ten_12345", "TENANT_WALLET", "CREDIT", 5000000L, "INITIAL", Instant.now()
        );
        LedgerPageResponse response = new LedgerPageResponse(
                "ten_12345", 5000000L, List.of(entry), 0, 20, 1L
        );
        when(adminService.getLedger(eq("ten_12345"), anyInt(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/admin/v1/tenants/ten_12345/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("ten_12345"))
                .andExpect(jsonPath("$.currentBalanceMicros").value(5000000L))
                .andExpect(jsonPath("$.entries[0].amountMicros").value(5000000L));
    }

    @Test
    void shouldProcessRefundSuccessfully() throws Exception {
        RefundRequest request = new RefundRequest("tx_orig_1", 50000L, "QUALITY_ISSUE");
        RefundResponse response = new RefundResponse(
                "ten_12345", "ref_tx_99", "tx_orig_1", 50000L, 1000000L, 1050000L, Instant.now()
        );
        when(ledgerService.processRefund(eq("ten_12345"), eq("tx_orig_1"), eq(50000L), eq("QUALITY_ISSUE")))
                .thenReturn(response);

        mockMvc.perform(post("/admin/v1/tenants/ten_12345/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("ten_12345"))
                .andExpect(jsonPath("$.refundTransactionId").value("ref_tx_99"))
                .andExpect(jsonPath("$.refundedAmountMicros").value(50000L))
                .andExpect(jsonPath("$.newBalanceMicros").value(1050000L));
    }

    @Test
    void shouldRunReconciliationSuccessfully() throws Exception {
        ReconciliationReport report = new ReconciliationReport(
                Instant.now(), 500L, 2000000L, 2000000L, 0L, true,
                10, 10, 0, List.of(), 0, true
        );
        when(reconciliationService.runAudit()).thenReturn(report);

        mockMvc.perform(post("/admin/v1/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemHealthy").value(true))
                .andExpect(jsonPath("$.ledgerBalanced").value(true))
                .andExpect(jsonPath("$.ledgerDeltaMicros").value(0L))
                .andExpect(jsonPath("$.tenantsAudited").value(10));
    }
}
