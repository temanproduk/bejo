package com.bejo.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminOtpVerifyRequestDto(
        @NotBlank(message = "email is required") @Email(message = "email is not valid") @Size(max = 255)
        String email,
        @NotBlank(message = "code is required") @Pattern(regexp = "\\d{8}", message = "code must be 8 digits")
        String code
) {}
