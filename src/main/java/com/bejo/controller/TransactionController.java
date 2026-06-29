package com.bejo.controller;

import com.bejo.dto.request.AddTransactionRequest;
import com.bejo.dto.response.ApiResponse;
import com.bejo.dto.response.TransactionCreatedData;
import com.bejo.dto.response.TransactionDto;
import com.bejo.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionCreatedData>> addTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddTransactionRequest request,
            Authentication authentication) {
        TransactionCreatedData data = transactionService.create(authentication.getName(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Transaksi berhasil ditambahkan", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionDto>>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            Authentication authentication) {
        List<TransactionDto> data = transactionService.getHistory(authentication.getName(), dateStart, dateEnd);
        return ResponseEntity.ok(ApiResponse.list(data));
    }
}
