package com.bejo.commerce.web;

import com.bejo.commerce.dto.AddTransactionRequestDto;
import com.bejo.commerce.dto.BulkAddTransactionRequestDto;
import com.bejo.commerce.dto.UpdateTransactionRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.commerce.dto.BulkTransactionResultResponseDto;
import com.bejo.commerce.dto.TransactionCreatedResponseDto;
import com.bejo.commerce.dto.TransactionResponseDto;
import com.bejo.commerce.dto.TransactionSummaryResponseDto;
import com.bejo.commerce.service.TransactionService;
import com.bejo.shared.util.Pagination;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

    /**
     * @param deviceId the device recording the transaction, from {@code X-Device-Id}. Used only to
     *   leave that one device out of the push fanned out for a PARSED transaction: it detected the
     *   payment and announced it locally already, while the merchant's other devices and their
     *   staff still need telling. <b>Optional</b> — an app build that predates the header must keep
     *   working, and then nobody is excluded (today's behaviour).
     */
    @PostMapping
    public ResponseEntity<ApiResponseDto<TransactionCreatedResponseDto>> addTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @Valid @RequestBody AddTransactionRequestDto request,
            Authentication authentication) {
        TransactionCreatedResponseDto data =
                transactionService.create(authentication.getName(), idempotencyKey, deviceId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Transaksi berhasil ditambahkan", data));
    }

    // Offline sync: push a batch of manually-entered transactions in one request.
    // Each item carries its own idempotencyKey so re-syncing the batch never
    // duplicates; the response reports per-item CREATED/DUPLICATE/FAILED.
    @PostMapping("/bulk")
    public ResponseEntity<ApiResponseDto<BulkTransactionResultResponseDto>> addTransactionsBulk(
            @Valid @RequestBody BulkAddTransactionRequestDto request,
            Authentication authentication) {
        BulkTransactionResultResponseDto data = transactionService.bulkCreate(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Sinkronisasi transaksi selesai", data));
    }

    // Single transaction with its sold-product line items (the history list omits items).
    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponseDto<TransactionResponseDto>> getTransaction(
            @PathVariable String transactionId,
            Authentication authentication) {
        TransactionResponseDto data = transactionService.get(authentication.getName(), transactionId);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    @PatchMapping("/{transactionId}")
    public ResponseEntity<ApiResponseDto<TransactionResponseDto>> updateTransaction(
            @PathVariable String transactionId,
            @Valid @RequestBody UpdateTransactionRequestDto request,
            Authentication authentication) {
        TransactionResponseDto data = transactionService.update(authentication.getName(), transactionId, request);
        return ResponseEntity.ok(ApiResponseDto.success("Transaksi berhasil diubah", data));
    }

    // Marks a QRIS sale that is waiting for payment as paid ("Sudah Terbayar"), by hand. Open to any user of
    // the business. 400 when the transaction was not awaiting payment; repeating it on a paid one is a no-op.
    @PostMapping("/{transactionId}/mark-paid")
    public ResponseEntity<ApiResponseDto<TransactionResponseDto>> markPaid(
            @PathVariable String transactionId,
            Authentication authentication) {
        TransactionResponseDto data = transactionService.markPaid(authentication.getName(), transactionId);
        return ResponseEntity.ok(ApiResponseDto.success("Transaksi ditandai sudah terbayar", data));
    }

    @DeleteMapping("/{transactionId}")
    public ResponseEntity<ApiResponseDto<Void>> deleteTransaction(
            @PathVariable String transactionId,
            Authentication authentication) {
        transactionService.delete(authentication.getName(), transactionId);
        return ResponseEntity.ok(ApiResponseDto.success("Transaksi berhasil dihapus", null));
    }

    // Dashboard totals (amount + count) for today / yesterday / this month. Needed
    // because the history list is paginated, so the client can't sum it locally.
    // Optional `date` = the client's local "today" (for timezone correctness);
    // defaults to the server date when omitted.
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseDto<TransactionSummaryResponseDto>> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        TransactionSummaryResponseDto data = transactionService.summary(authentication.getName(), date);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    // Pagination is optional: omit page/size to get page 1 of 50. The response
    // carries page/totalPages/total alongside the current page's items.
    @GetMapping
    public ResponseEntity<ApiResponseDto<List<TransactionResponseDto>>> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        Page<TransactionResponseDto> data = transactionService.getHistory(
                authentication.getName(), dateStart, dateEnd, Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }
}
