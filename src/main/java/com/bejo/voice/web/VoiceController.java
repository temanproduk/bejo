package com.bejo.voice.web;

import com.bejo.shared.web.ApiResponseDto;
import com.bejo.voice.dto.VoiceAmountRequestDto;
import com.bejo.voice.dto.VoiceAmountResponseDto;
import com.bejo.voice.service.VoiceAmountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceAmountService voiceAmountService;

    public VoiceController(VoiceAmountService voiceAmountService) {
        this.voiceAmountService = voiceAmountService;
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
}
