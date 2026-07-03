package com.bejo.config;

import com.bejo.service.JwtService;
import com.bejo.service.TokenBlacklistService;
import jakarta.servlet.http.HttpServletResponse;
import static org.springframework.security.config.Customizer.withDefaults;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;

    public SecurityConfig(JwtService jwtService,
                          TokenBlacklistService tokenBlacklistService,
                          // Qualifier needed: actuator registers a second RequestMappingHandlerMapping
                          // subclass, and an ambiguous provider silently resolves to null
                          @Qualifier("requestMappingHandlerMapping")
                          ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.handlerMappingProvider = handlerMappingProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(jwtService, tokenBlacklistService);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // HSTS: force HTTPS for a year (TLS terminates at the Cloud Run proxy;
                        // forward-headers-strategy lets Spring see the original https scheme)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(withDefaults())
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // JSON API returns no HTML/scripts — lock the CSP right down
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/snap/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    try {
                        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
                        // The entry point runs before DispatcherServlet, so the parsed
                        // request path getHandler() relies on is not cached yet
                        if (!ServletRequestPathUtils.hasParsedRequestPath(req)) {
                            ServletRequestPathUtils.parseAndCache(req);
                        }
                        if (mapping == null || mapping.getHandler(req) == null) {
                            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                            res.getWriter().write("{\"status\":0,\"message\":\"Route not found\"}");
                            return;
                        }
                    } catch (HttpRequestMethodNotSupportedException methodEx) {
                        res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                        res.getWriter().write("{\"status\":0,\"message\":\"Method not allowed: "
                                + methodEx.getMethod() + "\"}");
                        return;
                    } catch (Exception ignored) {
                        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        res.getWriter().write("{\"status\":0,\"message\":\"Route not found\"}");
                        return;
                    }
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.getWriter().write("{\"status\":0,\"message\":\"Unauthorized — include Authorization: Bearer <token> header\"}");
                }))
                .build();
    }
}
