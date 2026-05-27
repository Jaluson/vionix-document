package com.vionix.backend.device;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.AuthPrincipal;
import com.vionix.backend.common.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeviceAccessService {
    private final JdbcTemplate jdbcTemplate;

    public DeviceAccessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String visibleDevicePredicate(String alias, List<Object> params, long tenantId) {
        return visibleDevicePredicate(alias, params, tenantId, false);
    }

    public String visibleDevicePredicate(String alias, List<Object> params, long tenantId, boolean enabledOnly) {
        AuthPrincipal principal = SecurityContext.require();
        params.add(tenantId);
        StringBuilder predicate = new StringBuilder(alias).append(".tenant_id = ?");
        if (enabledOnly) {
            predicate.append(" AND ").append(alias).append(".status = 'ENABLED'");
        }
        if (hasTenantWideDeviceScope(principal)) {
            return predicate.toString();
        }
        if (hasGroupDeviceScope(principal)) {
            params.add(tenantId);
            params.add(principal.userId());
            predicate.append("""
                     AND EXISTS (
                       SELECT 1
                       FROM device_group_member vdg_m
                       JOIN device_group vdg_g ON vdg_g.tenant_id = vdg_m.tenant_id AND vdg_g.id = vdg_m.group_id
                       WHERE vdg_m.tenant_id = ?
                         AND vdg_m.device_id = %s.device_id
                         AND vdg_g.created_by = ?
                     )
                    """.formatted(alias));
            return predicate.toString();
        }
        params.add(principal.userId());
        predicate.append(" AND ").append(alias).append(".created_by = ?");
        return predicate.toString();
    }

    public void assertVisible(long tenantId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "deviceId is required");
        }
        List<Object> params = new java.util.ArrayList<>();
        String predicate = visibleDevicePredicate("d", params, tenantId);
        params.add(deviceId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device d WHERE " + predicate + " AND d.device_id = ?",
                Integer.class,
                params.toArray()
        );
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "device not found");
        }
    }

    public void assertEnabledVisible(long tenantId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "deviceId is required");
        }
        List<Object> params = new java.util.ArrayList<>();
        String predicate = visibleDevicePredicate("d", params, tenantId);
        params.add(deviceId);
        List<String> statuses = jdbcTemplate.query(
                "SELECT d.status FROM device d WHERE " + predicate + " AND d.device_id = ?",
                (rs, rowNum) -> rs.getString("status"),
                params.toArray()
        );
        if (statuses.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "device not found");
        }
        if (!"ENABLED".equals(statuses.getFirst())) {
            throw new ApiException(ErrorCode.DEVICE_DISABLED, "device is not enabled");
        }
    }

    public boolean hasTenantWideDeviceScope(AuthPrincipal principal) {
        return principal.isSuperAdmin()
                || "ALL".equalsIgnoreCase(principal.dataScope())
                || "TENANT".equalsIgnoreCase(principal.dataScope())
                || principal.hasPermission("data:device:tenant");
    }

    private boolean hasGroupDeviceScope(AuthPrincipal principal) {
        return "GROUP".equalsIgnoreCase(principal.dataScope())
                || principal.hasPermission("data:device:group");
    }
}
