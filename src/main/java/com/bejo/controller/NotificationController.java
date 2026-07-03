package com.bejo.controller;

import com.bejo.dto.request.SendNotificationRequest;
import com.bejo.dto.response.ApiResponse;
import com.bejo.service.NotificationService;
import com.google.firebase.messaging.FirebaseMessagingException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/test")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // Test-only: sends an FCM push built from the request to the given device.
    // Does not record a transaction — use the QRIS callback for real payments.
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> sendTestNotification(
            @Valid @RequestBody SendNotificationRequest request,
            Authentication authentication) throws FirebaseMessagingException {
        notificationService.send(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Test notification sent", null));
    }
}
