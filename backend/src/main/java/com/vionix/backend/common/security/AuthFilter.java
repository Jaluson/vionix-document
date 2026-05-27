package com.vionix.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vionix.backend.auth.TokenStateService;
import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final TokenStateService tokenStateService;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwtService, TokenStateService tokenStateService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tokenStateService = tokenStateService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = bearerToken(request);
            if (token != null) {
                JwtService.TokenClaims claims = jwtService.parse(token);
                if (tokenStateService.isBlacklisted(claims.jti())) {
                    throw new ApiException(ErrorCode.UNAUTHORIZED, "token has been logged out");
                }
                SecurityContext.set(claims.principal());
            } else if (isProtectedApi(request)) {
                throw new ApiException(ErrorCode.UNAUTHORIZED);
            }
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            writeError(response, exception.errorCode(), exception.getMessage());
        } finally {
            SecurityContext.clear();
        }
    }

    private boolean isProtectedApi(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return false;
        }
        return !path.equals("/api/auth/login")
                && !path.equals("/api/auth/refresh")
                && !path.equals("/api/security/public-key")
                && !path.equals("/api/system/ping");
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(errorCode, message)));
    }
}
