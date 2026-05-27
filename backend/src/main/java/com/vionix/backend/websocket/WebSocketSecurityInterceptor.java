package com.vionix.backend.websocket;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.AuthPrincipal;
import com.vionix.backend.common.security.JwtService;
import com.vionix.backend.auth.TokenStateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class WebSocketSecurityInterceptor implements ChannelInterceptor {
    private final JwtService jwtService;
    private final TokenStateService tokenStateService;
    private final JdbcTemplate jdbcTemplate;

    public WebSocketSecurityInterceptor(JwtService jwtService, TokenStateService tokenStateService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.tokenStateService = tokenStateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            AuthPrincipal principal = authenticate(accessor.getFirstNativeHeader("Authorization"));
            accessor.setUser(new StompPrincipal(principal));
            return message;
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            AuthPrincipal principal = principal(accessor);
            validateSubscription(principal, accessor.getDestination());
        }
        return message;
    }

    private AuthPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        JwtService.TokenClaims claims = jwtService.parse(authorization.substring("Bearer ".length()).trim());
        if (tokenStateService.isBlacklisted(claims.jti())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (!claims.principal().hasPermission("api:ws:subscribe")) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return claims.principal();
    }

    private AuthPrincipal principal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal stompPrincipal) {
            return stompPrincipal.authPrincipal();
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }

    private void validateSubscription(AuthPrincipal principal, String destination) {
        if (destination == null || !destination.startsWith("/topic/tenant/")) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String[] parts = destination.split("/");
        if (parts.length < 4) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        long tenantId = parseTenant(parts[3]);
        if (tenantId != principal.tenantId() && !principal.isSuperAdmin()) {
            throw new ApiException(ErrorCode.TENANT_MISMATCH);
        }
        boolean metrics = destination.endsWith("/metrics");
        boolean alerts = destination.endsWith("/alerts");
        if (metrics && !principal.hasPermission("api:metrics:view")) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (alerts && !principal.hasPermission("api:alert:view")) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (parts.length >= 6 && "device".equals(parts[4])) {
            assertEnabledDevice(tenantId, parts[5]);
        }
    }

    private long parseTenant(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid tenant in topic");
        }
    }

    private void assertEnabledDevice(long tenantId, String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE tenant_id = ? AND device_id = ? AND status = 'ENABLED'",
                Integer.class,
                tenantId,
                deviceId
        );
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.DEVICE_DISABLED, "device is not enabled or visible");
        }
    }
}
