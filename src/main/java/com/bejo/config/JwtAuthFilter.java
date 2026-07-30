package com.bejo.config;

import com.bejo.core.repository.UserRepository;
import com.bejo.core.service.JwtService;
import com.bejo.core.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Optional;

/**
 * Authenticates a request from its bearer token. A token is accepted only when all of the
 * following hold — the token is parsed <b>once</b> and the user looked up <b>once</b>:
 * <ol>
 *   <li>signature + expiry are valid;</li>
 *   <li>its {@code jti} is not blacklisted (logged out);</li>
 *   <li>the user still exists and is not soft-deleted (a released username can't be impersonated);</li>
 *   <li>its password-binding claim still matches the user's current password hash — so a password
 *       change (by the user or by a super admin) instantly invalidates tokens issued before it,
 *       on every device.</li>
 * </ol>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService,
                         TokenBlacklistService tokenBlacklistService,
                         UserRepository userRepository) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        jwtService.parse(authHeader.substring(7))
                .filter(claims -> !tokenBlacklistService.isBlacklisted(claims.jti()))
                .filter(this::passwordBindingStillMatches)
                .ifPresent(claims -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            claims.username(), null, Collections.emptyList()
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });

        chain.doFilter(request, response);
    }

    /**
     * One query resolves both "is this user still active?" (no row → soft-deleted or gone) and
     * "was this token issued against the current password?". A token minted before this claim
     * existed carries none and is rejected — it must be re-issued by logging in again.
     */
    private boolean passwordBindingStillMatches(JwtService.TokenClaims claims) {
        if (claims.passwordBinding() == null) {
            return false;
        }
        Optional<String> currentHash = userRepository.findPasswordHashByUsername(claims.username());
        return currentHash
                .map(jwtService::passwordBinding)
                .filter(expected -> MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        claims.passwordBinding().getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }
}
