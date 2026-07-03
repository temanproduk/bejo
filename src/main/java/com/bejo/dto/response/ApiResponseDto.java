package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseDto<T>(
        int status,
        String message,
        T data,
        Integer count
) {
    public static <T> ApiResponseDto<T> success(T data) {
        return new ApiResponseDto<>(1, null, data, null);
    }

    public static <T> ApiResponseDto<T> success(String message, T data) {
        return new ApiResponseDto<>(1, message, data, null);
    }

    public static <T> ApiResponseDto<List<T>> list(List<T> data) {
        return new ApiResponseDto<>(1, null, data, data.size());
    }

    public static ApiResponseDto<Void> error(String message) {
        return new ApiResponseDto<>(0, message, null, null);
    }
}
