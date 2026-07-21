package com.bejo.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/ads/deliveries/{id}/downloaded} — the app's report after trying to
 * download the audio. On {@code success=true} it echoes the file's {@code sha256} + {@code sizeBytes}
 * so the server can confirm integrity (→ READY) or flag a mismatch (→ ERROR). On
 * {@code success=false} (file missing / download failed) it may include an {@code error} note.
 */
public record AdDownloadReportRequestDto(
        @NotNull(message = "Status unduhan wajib diisi")
        Boolean success,

        @Size(max = 64, message = "sha256 tidak valid")
        String sha256,

        Long sizeBytes,

        @Size(max = 500, message = "Keterangan error maksimal 500 karakter")
        String error
) {}
