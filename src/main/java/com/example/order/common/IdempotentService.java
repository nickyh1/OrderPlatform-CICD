package com.example.order.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_PREFIX = "idempotent:";
    private static final long TOKEN_TTL_MINUTES = 5;

    /**
     * Generate a new idempotent token, store in Redis with TTL.
     */
    public String generateToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = TOKEN_PREFIX + token;
        redisTemplate.opsForValue().set(key, "1", TOKEN_TTL_MINUTES, TimeUnit.MINUTES);
        log.info("Idempotent token generated: {}", token);
        return token;
    }

    /**
     * Validate and consume token atomically.
     * Returns true if token is valid (first use), false if token is missing or already used.
     */
    public boolean validateAndConsume(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = TOKEN_PREFIX + token;
        // DELETE returns true if key existed and was removed
        Boolean deleted = redisTemplate.delete(key);
        boolean valid = Boolean.TRUE.equals(deleted);
        if (!valid) {
            log.warn("Idempotent token invalid or already consumed: {}", token);
        }
        return valid;
    }
}