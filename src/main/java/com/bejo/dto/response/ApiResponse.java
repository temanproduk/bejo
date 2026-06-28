package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int status,
        String message,
        T data,
        Integer count
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(1, null, data, null);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(1, message, data, null);
    }

    public static <T> ApiResponse<List<T>> list(List<T> data) {
        return new ApiResponse<>(1, null, data, data.size());
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(0, message, null, null);
    }
}
