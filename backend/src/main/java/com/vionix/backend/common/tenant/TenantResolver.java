package com.vionix.backend.common.tenant;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.AuthPrincipal;
import com.vionix.backend.common.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class TenantResolver {
    public static final String TARGET_TENANT_HEADER = "X-Tenant-Id";

    public long currentTenantId() {
        return SecurityContext.tenantId();
    }

    public long targetTenantId(HttpServletRequest request) {
        AuthPrincipal principal = SecurityContext.require();
        String requested = request.getHeader(TARGET_TENANT_HEADER);
        if (requested == null || requested.isBlank()) {
            return principal.tenantId();
        }
        if (!principal.isSuperAdmin()) {
            throw new ApiException(ErrorCode.TENANT_MISMATCH);
        }
        try {
            return Long.parseLong(requested);
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid X-Tenant-Id");
        }
    }
}
