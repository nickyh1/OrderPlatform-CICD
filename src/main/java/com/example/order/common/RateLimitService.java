package com.example.order.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Atomic sliding window rate limiter using a Lua script.
     * Non-atomic multi-step implementations allow concurrent requests to all pass
     * the count check simultaneously; this script serialises the check+add.
     *
     * @param key           rate limit key (e.g. "rate_limit:order:userId:1")
     * @param limit         max requests allowed in the window
     * @param windowSeconds time window in seconds
     * @return true if request is allowed, false if rate limited
     */
    public boolean isAllowed(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;
        // Unique member per request to avoid same-millisecond collisions overwriting each other
        String member = now + ":" + UUID.randomUUID();

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
        Long result = redisTemplate.execute(script,
                List.of(key),
                String.valueOf(windowStart),
                String.valueOf(limit),
                String.valueOf(now),
                member,
                String.valueOf(windowSeconds));

        if (result == null || result == 0L) {
            log.warn("Rate limited: key={}, limit={}", key, limit);
            return false;
        }
        return true;
    }

    private static final String SLIDING_WINDOW_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[2]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
            return 1
            """;
}