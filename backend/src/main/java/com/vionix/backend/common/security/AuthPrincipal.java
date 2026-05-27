package com.vionix.backend.common.security;

import java.util.Collections;
import java.util.Set;

public record AuthPrincipal(
        Long userId,
        Long tenantId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        String dataScope
) {
    public AuthPrincipal {
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(roles);
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(permissions);
        dataScope = dataScope == null || dataScope.isBlank() ? "SELF" : dataScope;
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission) || permissions.contains("*") || isSuperAdmin();
    }

    public boolean hasAnyPermission(String... candidates) {
        for (String candidate : candidates) {
            if (hasPermission(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSuperAdmin() {
        return roles.contains("SUPER_ADMIN") || permissions.contains("api:*");
    }
}
