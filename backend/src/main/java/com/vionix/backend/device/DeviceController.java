package com.vionix.backend.device;

import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.audit.AuditService;
import com.vionix.backend.common.jdbc.JsonSupport;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.RequirePermission;
import com.vionix.backend.common.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;
    private final DeviceAccessService deviceAccessService;
    private final AuditService auditService;

    public DeviceController(
            JdbcTemplate jdbcTemplate,
            JsonSupport jsonSupport,
            DeviceAccessService deviceAccessService,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
        this.deviceAccessService = deviceAccessService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequirePermission("api:device:view")
    public Result<PageResult<DeviceView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(deviceAccessService.visibleDevicePredicate("d", params, tenantId));
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (d.device_id LIKE ? OR d.name LIKE ?)");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND d.status = ?");
            params.add(status);
        }
        if (source != null && !source.isBlank()) {
            where.append(" AND d.source = ?");
            params.add(source);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device d WHERE " + where, Long.class, params.toArray());
        params.add(size);
        params.add(PageSupport.offset(page, size));
        List<DeviceView> items = jdbcTemplate.query(
                "SELECT * FROM device d WHERE " + where + " ORDER BY d.updated_at DESC LIMIT ? OFFSET ?",
                this::mapDevice,
                params.toArray()
        );
        return Result.ok(new PageResult<>(items, page, size, total == null ? 0 : total));
    }

    @GetMapping("/{deviceId}")
    @RequirePermission("api:device:view")
    public Result<DeviceView> get(@PathVariable String deviceId) {
        long tenantId = SecurityContext.tenantId();
        deviceAccessService.assertVisible(tenantId, deviceId);
        return Result.ok(jdbcTemplate.queryForObject(
                "SELECT * FROM device WHERE tenant_id = ? AND device_id = ?",
                this::mapDevice,
                tenantId,
                deviceId
        ));
    }

    @PostMapping
    @RequirePermission("api:device:manage")
    public Result<DeviceView> create(@Valid @RequestBody DevicePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        jdbcTemplate.update("""
                        INSERT INTO device (tenant_id, device_id, name, source, location, metadata, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId,
                payload.deviceId(),
                payload.name(),
                blankDefault(payload.source(), "mqtt"),
                payload.location(),
                jsonSupport.toJson(payload.metadata()),
                SecurityContext.require().userId()
        );
        auditService.success("DEVICE_CREATE", "device", payload.deviceId(), Map.of("deviceId", payload.deviceId()), request);
        return get(payload.deviceId());
    }

    @PutMapping("/{deviceId}")
    @RequirePermission("api:device:manage")
    public Result<DeviceView> update(@PathVariable String deviceId, @Valid @RequestBody DevicePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        deviceAccessService.assertVisible(tenantId, deviceId);
        jdbcTemplate.update("""
                        UPDATE device
                        SET name = ?, source = ?, location = ?, metadata = ?
                        WHERE tenant_id = ? AND device_id = ?
                        """,
                payload.name(),
                blankDefault(payload.source(), "mqtt"),
                payload.location(),
                jsonSupport.toJson(payload.metadata()),
                tenantId,
                deviceId
        );
        auditService.success("DEVICE_UPDATE", "device", deviceId, Map.of("deviceId", deviceId), request);
        return get(deviceId);
    }

    @PutMapping("/{deviceId}/status")
    @RequirePermission("api:device:manage")
    public Result<DeviceView> status(@PathVariable String deviceId, @RequestBody DeviceStatusPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        deviceAccessService.assertVisible(tenantId, deviceId);
        jdbcTemplate.update("UPDATE device SET status = ? WHERE tenant_id = ? AND device_id = ?", payload.status(), tenantId, deviceId);
        auditService.success("DEVICE_STATUS", "device", deviceId, Map.of("status", payload.status()), request);
        return get(deviceId);
    }

    private DeviceView mapDevice(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceView(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("device_id"),
                rs.getString("name"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("location"),
                jsonSupport.toMap(rs.getString("metadata")),
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DevicePayload(
            @NotBlank String deviceId,
            @NotBlank String name,
            String source,
            String location,
            Map<String, Object> metadata
    ) {
    }

    public record DeviceStatusPayload(@NotBlank String status) {
    }

    public record DeviceView(
            Long id,
            Long tenantId,
            String deviceId,
            String name,
            String source,
            String status,
            String location,
            Map<String, Object> metadata,
            String createdAt,
            String updatedAt
    ) {
    }
}
