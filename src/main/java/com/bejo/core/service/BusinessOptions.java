package com.bejo.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The configurable option lists for business {@code size} and {@code industry}
 * (from {@code app.business.*}). The allowed values and their defaults live in config so
 * they can change without a code change. Used to validate + default the fields at
 * registration.
 */
@Component
public class BusinessOptions {

    private final List<String> sizes;
    private final String defaultSize;
    private final List<String> industries;
    private final String defaultIndustry;

    public BusinessOptions(
            @Value("${app.business.sizes}") List<String> sizes,
            @Value("${app.business.default-size}") String defaultSize,
            @Value("${app.business.industries}") List<String> industries,
            @Value("${app.business.default-industry}") String defaultIndustry) {
        this.sizes = sizes.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.defaultSize = defaultSize.trim();
        this.industries = industries.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.defaultIndustry = defaultIndustry.trim();
    }

    public List<String> sizes() { return sizes; }
    public List<String> industries() { return industries; }

    /** Null/blank → the configured default; otherwise it must be one of the allowed sizes (else 400). */
    public String resolveSize(String input) {
        return resolve(input, defaultSize, sizes, "size");
    }

    /** Null/blank → the configured default; otherwise it must be one of the allowed industries (else 400). */
    public String resolveIndustry(String input) {
        return resolve(input, defaultIndustry, industries, "industry");
    }

    private String resolve(String input, String fallback, List<String> allowed, String field) {
        if (input == null || input.isBlank()) return fallback;
        String value = input.trim();
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " tidak valid. Pilihan: " + String.join(", ", allowed));
        }
        return value;
    }
}
