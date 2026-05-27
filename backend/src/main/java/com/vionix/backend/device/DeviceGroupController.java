package com.vionix.backend.device;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.audit.AuditService;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.RequirePermission;
import com.vionix.backend.common.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/device-groups")
public class DeviceGroupController {
    private final JdbcTemplate jdbcTemplate;
    private final DeviceAccessService deviceAccessService;
    private final AuditService auditService;

    public DeviceGroupController(JdbcTemplate jdbcTemplate, DeviceAccessService deviceAccessService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.deviceAccessService = deviceAccessService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequirePermission("api:device:view")
    public Result<PageResult<DeviceGroupView>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_group WHERE tenant_id = ?", Long.class, tenantId);
        List<DeviceGroupView> groups = jdbcTemplate.query(
                "SELECT * FROM device_group WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                this::mapGroup,
                tenantId,
                size,
                PageSupport.offset(page, size)
        );
        return Result.ok(new PageResult<>(groups, page, size, total == null ? 0 : total));
    }

    @PostMapping
    @RequirePermission("api:device:manage")
    public Result<DeviceGroupView> create(@RequestBody GroupPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        jdbcTemplate.update("""
                        INSERT INTO device_group (tenant_id, name, description, created_by)
                        VALUES (?, ?, ?, ?)
                        """,
                tenantId,
                payload.name(),
                payload.description(),
                SecurityContext.require().userId()
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        auditService.success("DEVICE_GROUP_CREATE", "device_group", id, Map.of("name", payload.name()), request);
        return Result.ok(loadGroup(tenantId, id));
    }

    @PutMapping("/{id}")
    @RequirePermission("api:device:manage")
    public Result<DeviceGroupView> update(@PathVariable long id, @RequestBody GroupPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureGroup(tenantId, id);
        jdbcTemplate.update("UPDATE device_group SET name = ?, description = ? WHERE tenant_id = ? AND id = ?",
                payload.name(), payload.description(), tenantId, id);
        auditService.success("DEVICE_GROUP_UPDATE", "device_group", id, Map.of("name", payload.name()), request);
        return Result.ok(loadGroup(tenantId, id));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("api:device:manage")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureGroup(tenantId, id);
        jdbcTemplate.update("DELETE FROM device_group_member WHERE tenant_id = ? AND group_id = ?", tenantId, id);
        jdbcTemplate.update("DELETE FROM device_group WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditService.success("DEVICE_GROUP_DELETE", "device_group", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PostMapping("/{id}/devices")
    @RequirePermission("api:device:manage")
    public Result<Void> bind(@PathVariable long id, @RequestBody DeviceBinding payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureGroup(tenantId, id);
        deviceAccessService.assertEnabledVisible(tenantId, payload.deviceId());
        jdbcTemplate.update("""
                        INSERT IGNORE INTO device_group_member (tenant_id, group_id, device_id)
                        VALUES (?, ?, ?)
                        """, tenantId, id, payload.deviceId());
        auditService.success("DEVICE_GROUP_BIND", "device_group", id, Map.of("deviceId", payload.deviceId()), request);
        return Result.ok();
    }

    @DeleteMapping("/{id}/devices")
    @RequirePermission("api:device:manage")
    public Result<Void> remove(@PathVariable long id, @RequestBody DeviceBinding payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureGroup(tenantId, id);
        jdbcTemplate.update("DELETE FROM device_group_member WHERE tenant_id = ? AND group_id = ? AND device_id = ?",
                tenantId, id, payload.deviceId());
        auditService.success("DEVICE_GROUP_UNBIND", "device_group", id, Map.of("deviceId", payload.deviceId()), request);
        return Result.ok();
    }

    private DeviceGroupView loadGroup(long tenantId, Long id) {
        return jdbcTemplate.queryForObject("SELECT * FROM device_group WHERE tenant_id = ? AND id = ?", this::mapGroup, tenantId, id);
    }

    private void ensureGroup(long tenantId, long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_group WHERE tenant_id = ? AND id = ?", Integer.class, tenantId, id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "device group not found");
        }
    }

    private DeviceGroupView mapGroup(ResultSet rs, int rowNum) throws SQLException {
        long tenantId = rs.getLong("tenant_id");
        long id = rs.getLong("id");
        List<String> deviceIds = jdbcTemplate.queryForList(
                "SELECT device_id FROM device_group_member WHERE tenant_id = ? AND group_id = ? ORDER BY device_id",
                String.class,
                tenantId,
                id
        );
        return new DeviceGroupView(
                id,
                tenantId,
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                deviceIds,
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    public record GroupPayload(@NotBlank String name, String description) {
    }

    public record DeviceBinding(@NotBlank String deviceId) {
    }

    public record DeviceGroupView(
            Long id,
            Long tenantId,
            String name,
            String description,
            String status,
            List<String> deviceIds,
            String createdAt,
            String updatedAt
    ) {
    }
}
