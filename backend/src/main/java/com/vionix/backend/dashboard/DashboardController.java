package com.vionix.backend.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.audit.AuditService;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.jdbc.JsonSupport;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.RequirePermission;
import com.vionix.backend.common.security.SecurityContext;
import com.vionix.backend.device.DeviceAccessService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;
    private final DeviceAccessService deviceAccessService;
    private final AuditService auditService;

    public DashboardController(JdbcTemplate jdbcTemplate, JsonSupport jsonSupport, DeviceAccessService deviceAccessService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
        this.deviceAccessService = deviceAccessService;
        this.auditService = auditService;
    }

    @GetMapping("/dashboards")
    @RequirePermission("api:dashboard:view")
    public Result<PageResult<DashboardView>> list(@RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dashboard WHERE tenant_id = ?", Long.class, tenantId);
        List<DashboardView> dashboards = jdbcTemplate.query(
                "SELECT * FROM dashboard WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                this::mapDashboard,
                tenantId,
                size,
                PageSupport.offset(page, size)
        );
        return Result.ok(new PageResult<>(dashboards, page, size, total == null ? 0 : total));
    }

    @GetMapping("/dashboards/{id}")
    @RequirePermission("api:dashboard:view")
    public Result<DashboardView> get(@PathVariable long id) {
        return Result.ok(load(SecurityContext.tenantId(), id));
    }

    @PostMapping("/dashboards")
    @RequirePermission("api:dashboard:manage")
    public Result<DashboardView> create(@RequestBody DashboardPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        String layoutJson = checkedLayout(payload.layout());
        jdbcTemplate.update("""
                        INSERT INTO dashboard (tenant_id, title, description, is_public, layout_config, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                tenantId,
                payload.title(),
                payload.description(),
                payload.effectivePublished(),
                layoutJson,
                SecurityContext.require().userId()
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        auditService.success("DASHBOARD_CREATE", "dashboard", id, Map.of("title", payload.title()), request);
        return Result.ok(load(tenantId, id));
    }

    @PutMapping("/dashboards/{id}")
    @RequirePermission("api:dashboard:manage")
    public Result<DashboardView> update(@PathVariable long id, @RequestBody DashboardPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureDashboard(tenantId, id);
        jdbcTemplate.update("""
                        UPDATE dashboard
                        SET title = ?, description = ?, is_public = ?, layout_config = ?
                        WHERE tenant_id = ? AND id = ?
                        """,
                payload.title(),
                payload.description(),
                payload.effectivePublished(),
                checkedLayout(payload.layout()),
                tenantId,
                id
        );
        auditService.success("DASHBOARD_UPDATE", "dashboard", id, Map.of("title", payload.title()), request);
        return Result.ok(load(tenantId, id));
    }

    @DeleteMapping("/dashboards/{id}")
    @RequirePermission("api:dashboard:manage")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureDashboard(tenantId, id);
        jdbcTemplate.update("DELETE FROM dashboard WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditService.success("DASHBOARD_DELETE", "dashboard", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PutMapping("/dashboards/{id}/publish")
    @RequirePermission("api:dashboard:manage")
    public Result<DashboardView> publish(@PathVariable long id, @RequestBody PublishPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureDashboard(tenantId, id);
        jdbcTemplate.update("UPDATE dashboard SET is_public = ? WHERE tenant_id = ? AND id = ?", payload.effectivePublished(), tenantId, id);
        auditService.success("DASHBOARD_PUBLISH", "dashboard", id, Map.of("published", payload.effectivePublished()), request);
        return Result.ok(load(tenantId, id));
    }

    @GetMapping("/dashboard-vars/devices")
    @RequirePermission("api:dashboard:view")
    public Result<List<DeviceOption>> devices() {
        long tenantId = SecurityContext.tenantId();
        ArrayList<Object> params = new ArrayList<>();
        String predicate = deviceAccessService.visibleDevicePredicate("d", params, tenantId, true);
        return Result.ok(jdbcTemplate.query(
                "SELECT device_id, name, source FROM device d WHERE " + predicate + " ORDER BY name",
                (rs, rowNum) -> new DeviceOption(rs.getString("device_id"), rs.getString("name"), rs.getString("source")),
                params.toArray()
        ));
    }

    @GetMapping("/dashboard-vars/metrics")
    @RequirePermission("api:dashboard:view")
    public Result<List<MetricOption>> metrics() {
        return Result.ok(List.of(
                new MetricOption("temperature", "温度", "C"),
                new MetricOption("humidity", "湿度", "%"),
                new MetricOption("light", "光照", "lux"),
                new MetricOption("voltage", "电压", "V")
        ));
    }

    private DashboardView load(long tenantId, long id) {
        ensureDashboard(tenantId, id);
        return jdbcTemplate.queryForObject("SELECT * FROM dashboard WHERE tenant_id = ? AND id = ?", this::mapDashboard, tenantId, id);
    }

    private void ensureDashboard(long tenantId, long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dashboard WHERE tenant_id = ? AND id = ?", Integer.class, tenantId, id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "dashboard not found");
        }
    }

    private String checkedLayout(Map<String, Object> layout) {
        String json = jsonSupport.toJson(layout);
        if (json.toLowerCase(java.util.Locale.ROOT).contains("tenant_id")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "layout_config must not contain tenant_id");
        }
        return json;
    }

    private DashboardView mapDashboard(ResultSet rs, int rowNum) throws SQLException {
        return new DashboardView(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getBoolean("is_public"),
                jsonSupport.toMap(rs.getString("layout_config")),
                rs.getLong("created_by"),
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    public record DashboardPayload(
            @NotBlank String title,
            String description,
            @JsonProperty("public") Boolean isPublic,
            Boolean published,
            Map<String, Object> layout
    ) {
        public boolean effectivePublished() {
            return Boolean.TRUE.equals(published) || Boolean.TRUE.equals(isPublic);
        }
    }

    public record PublishPayload(Boolean published, @JsonProperty("public") Boolean isPublic) {
        public boolean effectivePublished() {
            return Boolean.TRUE.equals(published) || Boolean.TRUE.equals(isPublic);
        }
    }

    public record DashboardView(
            Long id,
            Long tenantId,
            String title,
            String description,
            @JsonProperty("public") boolean isPublic,
            Map<String, Object> layout,
            Long creatorId,
            String createdAt,
            String updatedAt
    ) {
        public boolean published() {
            return isPublic;
        }
    }

    public record DeviceOption(String deviceId, String name, String source) {
    }

    public record MetricOption(String field, String label, String unit) {
    }
}
