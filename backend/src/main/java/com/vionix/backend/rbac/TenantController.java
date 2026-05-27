package com.vionix.backend.rbac;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.audit.AuditService;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.jdbc.JsonSupport;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.RequirePermission;
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
import java.util.Map;

@RestController
@RequestMapping("/api/tenants")
@RequirePermission("api:tenant:manage")
public class TenantController {
    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;
    private final AuditService auditService;

    public TenantController(JdbcTemplate jdbcTemplate, JsonSupport jsonSupport, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<PageResult<TenantView>> list(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant", Long.class);
        return Result.ok(new PageResult<>(
                jdbcTemplate.query("SELECT * FROM tenant ORDER BY id DESC LIMIT ? OFFSET ?", this::mapTenant, size, PageSupport.offset(page, size)),
                page,
                size,
                total == null ? 0 : total
        ));
    }

    @GetMapping("/{id}")
    public Result<TenantView> get(@PathVariable long id) {
        return Result.ok(load(id));
    }

    @PostMapping
    public Result<TenantView> create(@RequestBody TenantPayload payload, HttpServletRequest request) {
        jdbcTemplate.update("""
                INSERT INTO tenant (name, code, status, config)
                VALUES (?, ?, ?, ?)
                """, payload.name(), payload.code(), blankDefault(payload.status(), "ENABLED"), jsonSupport.toJson(payload.config()));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        auditService.success("TENANT_CREATE", "tenant", id, Map.of("code", payload.code()), request);
        return Result.ok(load(id));
    }

    @PutMapping("/{id}")
    public Result<TenantView> update(@PathVariable long id, @RequestBody TenantPayload payload, HttpServletRequest request) {
        ensureTenant(id);
        jdbcTemplate.update("UPDATE tenant SET name = ?, code = ?, status = ?, config = ? WHERE id = ?",
                payload.name(), payload.code(), blankDefault(payload.status(), "ENABLED"), jsonSupport.toJson(payload.config()), id);
        auditService.success("TENANT_UPDATE", "tenant", id, Map.of("code", payload.code()), request);
        return Result.ok(load(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        ensureTenant(id);
        if (id == 1L) {
            throw new ApiException(ErrorCode.CONFLICT, "default tenant cannot be deleted");
        }
        jdbcTemplate.update("UPDATE tenant SET status = 'DISABLED' WHERE id = ?", id);
        auditService.success("TENANT_DISABLE", "tenant", id, Map.of("id", id), request);
        return Result.ok();
    }

    private TenantView load(Long id) {
        ensureTenant(id);
        return jdbcTemplate.queryForObject("SELECT * FROM tenant WHERE id = ?", this::mapTenant, id);
    }

    private void ensureTenant(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "tenant not found");
        }
    }

    private TenantView mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new TenantView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("status"),
                jsonSupport.toMap(rs.getString("config")),
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record TenantPayload(@NotBlank String name, @NotBlank String code, String status, Map<String, Object> config) {
    }

    public record TenantView(Long id, String name, String code, String status, Map<String, Object> config, String createdAt, String updatedAt) {
    }
}
