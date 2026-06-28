package com.bejo.service;

import com.bejo.entity.TokenBlacklist;
import com.bejo.repository.TokenBlacklistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final TokenBlacklistRepository repository;

    public TokenBlacklistService(TokenBlacklistRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void blacklist(String jti, LocalDateTime expiresAt) {
        TokenBlacklist entry = new TokenBlacklist();
        entry.setJti(jti);
        entry.setExpiresAt(expiresAt);
        repository.save(entry);
    }

    public boolean isBlacklisted(String jti) {
        return repository.existsByJti(jti);
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void purgeExpired() {
        repository.deleteAllByExpiresAtBefore(LocalDateTime.now());
        log.debug("[BLACKLIST] Purged expired token entries");
    }
}
