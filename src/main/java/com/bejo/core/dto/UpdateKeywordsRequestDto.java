package com.bejo.core.dto;

import java.util.List;

/**
 * Updates the notification keyword filters. Partial update: a {@code null} field is
 * left unchanged; an empty list clears that keyword list. Keyword-level validation
 * (non-blank, size caps) is enforced in the service.
 */
public record UpdateKeywordsRequestDto(
        List<String> includeKeywords,
        List<String> excludeKeywords,
        Boolean blacklistEnabled,
        Boolean whitelistEnabled
) {
    public boolean isEmpty() {
        return includeKeywords == null && excludeKeywords == null
                && blacklistEnabled == null && whitelistEnabled == null;
    }
}
