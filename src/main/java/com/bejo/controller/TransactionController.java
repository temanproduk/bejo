package com.bejo.controller;

import com.bejo.dto.request.AddTransactionRequestDto;
import com.bejo.dto.request.UpdateTransactionRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.TransactionCreatedResponseDto;
import com.bejo.dto.response.TransactionResponseDto;
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
    public ResponseEntity<ApiResponseDto<TransactionCreatedResponseDto>> addTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddTransactionRequestDto request,
            Authentication authentication) {
        TransactionCreatedResponseDto data = transactionService.create(authentication.getName(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Transaksi berhasil ditambahkan", data));
    }

    @PatchMapping("/{transactionId}")
    public ResponseEntity<ApiResponseDto<TransactionResponseDto>> updateTransaction(
            @PathVariable String transactionId,
            @Valid @RequestBody UpdateTransactionRequestDto request,
            Authentication authentication) {
        TransactionResponseDto data = transactionService.update(authentication.getName(), transactionId, request);
        return ResponseEntity.ok(ApiResponseDto.success("Transaksi berhasil diubah", data));
    }

    @DeleteMapping("/{transactionId}")
    public ResponseEntity<ApiResponseDto<Void>> deleteTransaction(
            @PathVariable String transactionId,
            Authentication authentication) {
        transactionService.delete(authentication.getName(), transactionId);
        return ResponseEntity.ok(ApiResponseDto.success("Transaksi berhasil dihapus", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<TransactionResponseDto>>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            Authentication authentication) {
        List<TransactionResponseDto> data = transactionService.getHistory(authentication.getName(), dateStart, dateEnd);
        return ResponseEntity.ok(ApiResponseDto.list(data));
    }
}
