package com.bejo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Lets the admin dashboard — a static site on another origin
 * ({@code https://dashboard.bejo.temanproduk.com}) — call {@code /api/admin/**} from the browser.
 * Nothing else is opened: only those paths, and only the origins in {@code app.admin.dashboard-origins}.
 *
 * <p>Registered at the very front of the filter chain because a browser's preflight {@code OPTIONS}
 * cannot carry {@code X-API-TOKEN}: it has to be answered here, before {@link ApiTokenFilter} would
 * reject it. Being first also puts the CORS headers on the 401s the later filters write, so the
 * dashboard can read them instead of seeing an opaque network error.
 */
@Configuration
public class AdminCorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> adminCorsFilter(
            @Value("${app.admin.dashboard-origins:}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim).filter(o -> !o.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-TOKEN"));
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/admin/**", cors);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
