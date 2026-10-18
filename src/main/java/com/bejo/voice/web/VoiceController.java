package com.bejo.voice.web;

import com.bejo.shared.web.ApiResponseDto;
import com.bejo.voice.dto.VoiceAmountRequestDto;
import com.bejo.voice.dto.VoiceAmountResponseDto;
import com.bejo.voice.dto.VoiceModelsResponseDto;
import com.bejo.voice.dto.VoiceTransactionRequestDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.VoiceAmountService;
import com.bejo.voice.service.VoiceModelsService;
import com.bejo.voice.service.VoiceTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceAmountService voiceAmountService;
    private final VoiceModelsService voiceModelsService;
    private final VoiceTransactionService voiceTransactionService;

    public VoiceController(VoiceAmountService voiceAmountService, VoiceModelsService voiceModelsService,
                           VoiceTransactionService voiceTransactionService) {
        this.voiceAmountService = voiceAmountService;
        this.voiceModelsService = voiceModelsService;
        this.voiceTransactionService = voiceTransactionService;
    }

    // "Say the amount": a short voice clip → what was heard + the rupiah amount. Any signed-in user.
    @PostMapping("/amount")
    public ResponseEntity<ApiResponseDto<VoiceAmountResponseDto>> recogniseAmount(
            @Valid @RequestBody VoiceAmountRequestDto request,
            Authentication authentication) {
        VoiceAmountResponseDto data = voiceAmountService.recogniseAmount(authentication.getName(), request);
        String message = data.amount() != null ? "Nominal terdengar"
                : data.transcript() != null ? "Nominal tidak terdengar jelas" : "Suara tidak terdengar";
        return ResponseEntity.ok(ApiResponseDto.success(message, data));
    }

    // "Say the sale": a spoken sentence (+ the app's product list) → the amount, payment method, customer, merchant and
    // products Gemini understood, for a confirmation screen. Nothing is saved here. Any signed-in user.
    @PostMapping("/transaction")
    public ResponseEntity<ApiResponseDto<VoiceTransactionResponseDto>> understandTransaction(
            @Valid @RequestBody VoiceTransactionRequestDto request,
            Authentication authentication) {
        VoiceTransactionResponseDto data = voiceTransactionService.understand(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success(
                data.understood() ? "Transaksi terdengar" : "Transaksi tidak terdengar jelas", data));
    }

    // The voice-converter model files for "Suara Kamu Sendiri": names, sizes, SHA-256 and signed download links. Any signed-in user.
    @GetMapping("/models/converter")
    public ResponseEntity<ApiResponseDto<VoiceModelsResponseDto>> converterModels(Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDto.success("Daftar model suara", voiceModelsService.converterModels(authentication.getName())));
    }
}
