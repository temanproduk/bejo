package com.bejo.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The authenticated user's own account bundle — profile + role + (shared) business, bank,
 * picture, and app settings. Same shape as the login response minus the token fields, so
 * a client can refresh its full state without re-authenticating. {@code id} lets the
 * client identify itself within the members list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeResponseDto(
        Long id,
        String username,
        String accountType,
        String name,
        String email,
        boolean isEmailVerified,
        String phone,
        boolean isPhoneVerified,
        String businessRole,
        // True when this user has their OWN QRIS assigned (a user_qris row) rather than sharing
        // the business QRIS — i.e. they run a separate QRIS merchant app and detect their own
        // incoming payments, so the app may offer them the external-notification sound toggle.
        boolean hasOwnQris,
        String businessName,
        String businessAddress,
        String bankName,
        String bankNumber,
        String bankAccountName,
        String profilePicture,
        AccountSettingsResponseDto accountSettings
) {}
