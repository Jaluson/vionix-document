package com.vionix.backend.common.security;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;

import java.util.Optional;

public final class SecurityContext {
    private static final ThreadLocal<AuthPrincipal> CURRENT = new ThreadLocal<>();

    private SecurityContext() {
    }

    public static void set(AuthPrincipal principal) {
        CURRENT.set(principal);
    }

    public static Optional<AuthPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static AuthPrincipal require() {
        return current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    public static Long tenantId() {
        return require().tenantId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
