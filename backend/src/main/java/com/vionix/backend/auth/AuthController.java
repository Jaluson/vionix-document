package com.vionix.backend.auth;

import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.security.JwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private static final String REFRESH_COOKIE = "refreshToken";

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/auth/login")
    public org.springframework.http.ResponseEntity<Result<AuthService.LoginResponse>> login(
            @Valid @RequestBody LoginPayload payload,
            HttpServletRequest request
    ) {
        AuthService.AuthResult result = authService.login(
                new AuthService.LoginRequest(
                        payload.tenantCode(),
                        payload.username(),
                        payload.password(),
                        payload.captcha(),
                        payload.deviceInfo()
                )
        );
        return withRefreshCookie(request, result, Result.ok(result.responseBody()));
    }

    @PostMapping("/auth/refresh")
    public org.springframework.http.ResponseEntity<Result<AuthService.LoginResponse>> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request
    ) {
        AuthService.AuthResult result = authService.refresh(refreshToken, request.getHeader("User-Agent"));
        return withRefreshCookie(request, result, Result.ok(result.responseBody()));
    }

    @DeleteMapping("/auth/logout")
    public org.springframework.http.ResponseEntity<Result<Void>> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        JwtService.TokenClaims claims = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            claims = jwtService.parse(authorization.substring("Bearer ".length()).trim());
        }
        authService.logout(refreshToken, claims);
        ResponseCookie expired = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(Result.ok());
    }

    @GetMapping("/security/public-key")
    public Result<Map<String, Object>> publicKey() {
        return Result.ok(Map.of("enabled", false));
    }

    private org.springframework.http.ResponseEntity<Result<AuthService.LoginResponse>> withRefreshCookie(
            HttpServletRequest request,
            AuthService.AuthResult authResult,
            Result<AuthService.LoginResponse> body
    ) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, authResult.refreshToken())
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .build();
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    public record LoginPayload(
            String tenantCode,
            @NotBlank String username,
            @NotBlank String password,
            String captcha,
            String deviceInfo
    ) {
    }
}
