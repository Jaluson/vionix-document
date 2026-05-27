package com.vionix.backend.auth;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.AuthPrincipal;
import com.vionix.backend.common.security.JwtService;
import com.vionix.backend.common.security.Passwords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuthService {
    private final JdbcTemplate jdbcTemplate;
    private final PrincipalLoader principalLoader;
    private final JwtService jwtService;
    private final TokenStateService tokenStateService;
    private final VionixProperties properties;

    public AuthService(
            JdbcTemplate jdbcTemplate,
            PrincipalLoader principalLoader,
            JwtService jwtService,
            TokenStateService tokenStateService,
            VionixProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.principalLoader = principalLoader;
        this.jwtService = jwtService;
        this.tokenStateService = tokenStateService;
        this.properties = properties;
    }

    public AuthResult login(LoginRequest request) {
        UserPasswordRow user = findUser(request);
        if (!Passwords.verifyPassword(request.password(), user.password(), user.passwordSalt())) {
            tokenStateService.recordLoginFailure(user.tenantId(), request.username());
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid username or password");
        }
        tokenStateService.clearLoginFailure(user.tenantId(), request.username());
        AuthPrincipal principal = principalLoader.loadByUserId(user.id());
        return issueTokens(principal, request.deviceInfo());
    }

    public AuthResult refresh(String refreshToken, String deviceInfo) {
        if (refreshToken == null || refreshToken.isBlank() || !tokenStateService.refreshTokenExists(refreshToken)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid refresh token");
        }
        long userId = tokenStateService.userIdForRefreshToken(refreshToken);
        tokenStateService.deleteRefreshToken(refreshToken);
        return issueTokens(principalLoader.loadByUserId(userId), deviceInfo);
    }

    public void logout(String refreshToken, JwtService.TokenClaims accessClaims) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenStateService.deleteRefreshToken(refreshToken);
        }
        if (accessClaims != null) {
            tokenStateService.blacklistAccessToken(accessClaims.jti(), accessClaims.expiresAt());
        }
    }

    private AuthResult issueTokens(AuthPrincipal principal, String deviceInfo) {
        JwtService.IssuedAccessToken accessToken = jwtService.issue(principal);
        String refreshToken = Passwords.randomToken();
        Instant refreshExpiresAt = Instant.now().plus(properties.getSecurity().getRefreshTokenTtl());
        tokenStateService.storeRefreshToken(principal.userId(), refreshToken, deviceInfo, refreshExpiresAt);
        return new AuthResult(
                accessToken.token(),
                refreshToken,
                "Bearer",
                properties.getSecurity().getAccessTokenTtl().toSeconds(),
                new LoginUser(
                        principal.userId(),
                        principal.username(),
                        principal.username(),
                        principal.tenantId(),
                        principal.roles(),
                        principal.permissions()
                )
        );
    }

    private UserPasswordRow findUser(LoginRequest request) {
        List<UserPasswordRow> rows;
        if (request.tenantCode() == null || request.tenantCode().isBlank()) {
            rows = jdbcTemplate.query("""
                            SELECT u.id, u.tenant_id, u.password, u.password_salt
                            FROM `user` u
                            JOIN tenant t ON t.id = u.tenant_id
                            WHERE u.username = ? AND u.status = 'ENABLED' AND t.status = 'ENABLED'
                            ORDER BY u.id
                            LIMIT 2
                            """,
                    (rs, rowNum) -> new UserPasswordRow(
                            rs.getLong("id"),
                            rs.getLong("tenant_id"),
                            rs.getString("password"),
                            rs.getString("password_salt")
                    ),
                    request.username()
            );
        } else {
            rows = jdbcTemplate.query("""
                            SELECT u.id, u.tenant_id, u.password, u.password_salt
                            FROM `user` u
                            JOIN tenant t ON t.id = u.tenant_id
                            WHERE u.username = ? AND t.code = ? AND u.status = 'ENABLED' AND t.status = 'ENABLED'
                            ORDER BY u.id
                            LIMIT 1
                            """,
                    (rs, rowNum) -> new UserPasswordRow(
                            rs.getLong("id"),
                            rs.getLong("tenant_id"),
                            rs.getString("password"),
                            rs.getString("password_salt")
                    ),
                    request.username(),
                    request.tenantCode()
            );
        }
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid username or password");
        }
        if (rows.size() > 1) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "tenantCode is required for duplicated usernames");
        }
        return rows.getFirst();
    }

    public record LoginRequest(String tenantCode, String username, String password, String captcha, String deviceInfo) {
    }

    public record LoginUser(
            Long id,
            String username,
            String nickname,
            Long tenantId,
            java.util.Set<String> roles,
            java.util.Set<String> permissions
    ) {
    }

    public record AuthResult(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            LoginUser user
    ) {
        public LoginResponse responseBody() {
            return new LoginResponse(accessToken, tokenType, expiresIn, user);
        }
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, LoginUser user) {
    }

    private record UserPasswordRow(Long id, Long tenantId, String password, String passwordSalt) {
    }
}
