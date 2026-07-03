package com.bejo.controller;

import com.bejo.dto.request.QrMpmNotifyRequest;
import com.bejo.dto.response.QrMpmNotifyResponse;
import com.bejo.service.QrisCallbackService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound QRIS payment callback (SNAP-like QR MPM notify) from the QRIS merchant
 * service. Server-to-server, so no X-API-TOKEN or JWT — exempted in
 * ApiTokenFilter and SecurityConfig. Records the payment as a transaction and
 * stores the raw payload in the transaction's additional_info column.
 */
@RestController
public class QrisCallbackController {

    private final QrisCallbackService callbackService;

    public QrisCallbackController(QrisCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping(path = "/snap/v1.0/qr/qr-mpm-notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QrMpmNotifyResponse> notify(
            @RequestHeader(value = "X-TIMESTAMP", required = false) String xTimestamp,
            @Valid @RequestBody QrMpmNotifyRequest request) {
        return ResponseEntity.ok(callbackService.processNotify(request, xTimestamp));
    }
}
