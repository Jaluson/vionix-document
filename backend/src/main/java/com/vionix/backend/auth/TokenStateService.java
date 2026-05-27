package com.vionix.backend.auth;

import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.common.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenStateService {
    private static final Logger log = LoggerFactory.getLogger(TokenStateService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Optional<StringRedisTemplate> redisTemplate;
    private final VionixProperties properties;
    private final ConcurrentHashMap<String, Instant> localBlacklist = new ConcurrentHashMap<>();

    public TokenStateService(
            JdbcTemplate jdbcTemplate,
            Optional<StringRedisTemplate> redisTemplate,
            VionixProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public String hashRefreshToken(String token) {
        return Passwords.sha256Hex(properties.getSecurity().getTokenHashSalt() + ":" + token);
    }

    public void storeRefreshToken(long userId, String token, String deviceInfo, Instant expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO token_auth (user_id, token_hash, device_info, expires_at) VALUES (?, ?, ?, ?)",
                userId,
                hashRefreshToken(token),
                deviceInfo,
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        );
    }

    public boolean refreshTokenExists(String token) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM token_auth WHERE token_hash = ? AND expires_at > UTC_TIMESTAMP()",
                Integer.class,
                hashRefreshToken(token)
        );
        return count != null && count > 0;
    }

    public Long userIdForRefreshToken(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM token_auth WHERE token_hash = ? AND expires_at > UTC_TIMESTAMP()",
                Long.class,
                hashRefreshToken(token)
        );
    }

    public void deleteRefreshToken(String token) {
        jdbcTemplate.update("DELETE FROM token_auth WHERE token_hash = ?", hashRefreshToken(token));
    }

    public void blacklistAccessToken(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        String key = "auth:blacklist:" + jti;
        try {
            if (redisTemplate.isPresent()) {
                redisTemplate.get().opsForValue().set(key, "1", ttl);
            } else {
                localBlacklist.put(jti, expiresAt);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis blacklist write failed; falling back to local blacklist for current instance.");
            localBlacklist.put(jti, expiresAt);
        }
    }

    public boolean isBlacklisted(String jti) {
        String key = "auth:blacklist:" + jti;
        try {
            if (redisTemplate.isPresent()) {
                Boolean present = redisTemplate.get().hasKey(key);
                return Boolean.TRUE.equals(present);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis blacklist read failed; using local blacklist fallback.");
        }
        localBlacklist.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getValue()));
        return localBlacklist.containsKey(jti);
    }

    public void recordLoginFailure(long tenantId, String username) {
        String key = "auth:fail:" + tenantId + ":" + username;
        try {
            redisTemplate.ifPresent(redis -> redis.opsForValue().increment(key));
        } catch (RuntimeException exception) {
            log.warn("Unable to record login failure in Redis.");
        }
    }

    public void clearLoginFailure(long tenantId, String username) {
        String key = "auth:fail:" + tenantId + ":" + username;
        try {
            redisTemplate.ifPresent(redis -> redis.delete(key));
        } catch (RuntimeException exception) {
            log.warn("Unable to clear login failure in Redis.");
        }
    }

    public void pruneExpiredRefreshTokens() {
        try {
            jdbcTemplate.update("DELETE FROM token_auth WHERE expires_at <= UTC_TIMESTAMP()");
        } catch (DataAccessException exception) {
            log.debug("Token prune skipped because token_auth is unavailable.");
        }
    }
}
