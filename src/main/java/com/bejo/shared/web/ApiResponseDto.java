package com.bejo.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseDto<T>(
        int status,
        String message,
        T data,
        Integer count,
        Integer page,
        Integer totalPages,
        Long total
) {
    public static <T> ApiResponseDto<T> success(T data) {
        return new ApiResponseDto<>(1, null, data, null, null, null, null);
    }

    public static <T> ApiResponseDto<T> success(String message, T data) {
        return new ApiResponseDto<>(1, message, data, null, null, null, null);
    }

    public static <T> ApiResponseDto<List<T>> list(List<T> data) {
        return new ApiResponseDto<>(1, null, data, data.size(), null, null, null);
    }

    /**
     * Paginated list response: {@code data} is the current page's items, {@code count}
     * is how many are in this page, and {@code page}/{@code totalPages}/{@code total}
     * describe the whole result set ({@code page} is 1-based).
     */
    public static <T> ApiResponseDto<List<T>> page(Page<T> page) {
        return new ApiResponseDto<>(1, null, page.getContent(), page.getNumberOfElements(),
                page.getNumber() + 1, page.getTotalPages(), page.getTotalElements());
    }

    public static ApiResponseDto<Void> error(String message) {
        return new ApiResponseDto<>(0, message, null, null, null, null, null);
    }
}
