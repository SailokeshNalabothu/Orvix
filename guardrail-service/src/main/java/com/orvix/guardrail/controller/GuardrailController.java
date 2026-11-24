package com.orvix.guardrail.controller;

import com.orvix.common.contract.dto.PromptInspectionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.guardrail.service.GuardrailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class GuardrailController {

    private final GuardrailService guardrailService;

    @PostMapping("/inspect")
    public ResponseEntity<PromptInspectionResponse> inspectPrompt(
            @Valid @RequestBody PromptInspectionRequest request
    ) {
        PromptInspectionResponse response = guardrailService.inspect(request);

        if (PromptInspectionResponse.STATUS_BLOCKED.equals(response.status())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
