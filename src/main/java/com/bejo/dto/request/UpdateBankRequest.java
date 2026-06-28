package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateBankRequest(
        @NotBlank(message = "Nama bank wajib diisi")
        @Size(max = 100, message = "Nama bank maksimal 100 karakter")
        String bankName,

        @NotBlank(message = "Nomor rekening wajib diisi")
        @Pattern(regexp = "^[0-9]{6,20}$",
                 message = "Nomor rekening harus berupa angka (6-20 digit)")
        String bankNumber,

        @NotBlank(message = "Nama pemilik rekening wajib diisi")
        @Size(min = 2, max = 100, message = "Nama pemilik rekening harus antara 2 hingga 100 karakter")
        String bankAccountName
) {}
