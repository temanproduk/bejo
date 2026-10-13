package com.bejo.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminOtpRequestDto(
        @NotBlank(message = "email is required") @Email(message = "email is not valid") @Size(max = 255)
        String email
) {}
