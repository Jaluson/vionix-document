package com.vionix.backend.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final VionixProperties properties;
    private final byte[] secret;

    public JwtService(ObjectMapper objectMapper, VionixProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        String configured = properties.getSecurity().getJwtSecret();
        if (configured == null || configured.isBlank()) {
            log.warn("AUTH_JWT_SECRET is empty; generated an in-memory development signing key.");
            configured = UUID.randomUUID() + UUID.randomUUID().toString();
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public IssuedAccessToken issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getSecurity().getAccessTokenTtl());
        String jti = UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(principal.userId()));
        payload.put("tenantId", principal.tenantId());
        payload.put("username", principal.username());
        payload.put("roles", principal.roles());
        payload.put("permissions", principal.permissions());
        payload.put("dataScope", principal.dataScope());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("jti", jti);

        String unsigned = encodeJson(header) + "." + encodeJson(payload);
        String signature = URL_ENCODER.encodeToString(Passwords.hmacSha256(secret, unsigned));
        return new IssuedAccessToken(unsigned + "." + signature, jti, expiresAt);
    }

    public TokenClaims parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid token");
            }
            String unsigned = parts[0] + "." + parts[1];
            String expected = URL_ENCODER.encodeToString(Passwords.hmacSha256(secret, unsigned));
            if (!MessageDigests.constantTimeEquals(expected, parts[2])) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid token signature");
            }

            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(parts[1]), new TypeReference<>() {
            });
            long exp = ((Number) payload.get("exp")).longValue();
            Instant expiresAt = Instant.ofEpochSecond(exp);
            if (Instant.now().isAfter(expiresAt)) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "token expired");
            }

            AuthPrincipal principal = new AuthPrincipal(
                    Long.valueOf(String.valueOf(payload.get("sub"))),
                    asLong(payload.get("tenantId")),
                    String.valueOf(payload.get("username")),
                    asStringSet(payload.get("roles")),
                    asStringSet(payload.get("permissions")),
                    String.valueOf(payload.getOrDefault("dataScope", "SELF"))
            );
            return new TokenClaims(principal, String.valueOf(payload.get("jti")), expiresAt);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid token");
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode JWT", exception);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private Set<String> asStringSet(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    public record IssuedAccessToken(String token, String jti, Instant expiresAt) {
    }

    public record TokenClaims(AuthPrincipal principal, String jti, Instant expiresAt) {
    }

    private static final class MessageDigests {
        private MessageDigests() {
        }

        static boolean constantTimeEquals(String left, String right) {
            return java.security.MessageDigest.isEqual(
                    left.getBytes(StandardCharsets.UTF_8),
                    right.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}
