package com.bejo.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Builds a {@link Pageable} from the optional {@code page}/{@code size} query params
 * used by the list endpoints. Pagination is optional: a missing/invalid {@code page}
 * falls back to page 1, and {@code size} defaults to {@link #DEFAULT_SIZE} (capped at
 * {@link #MAX_SIZE}). Page numbers are 1-based in the API; Spring Data is 0-based, so
 * this subtracts one.
 */
public final class Pagination {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    private Pagination() {}

    public static Pageable of(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(p - 1, s);
    }
}
