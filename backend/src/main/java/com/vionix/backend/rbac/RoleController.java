package com.vionix.backend.rbac;

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
@RequestMapping("/api/roles")
@RequirePermission("api:rbac:manage")
public class RoleController {
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public RoleController(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<PageResult<RoleView>> list(@RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `role` WHERE tenant_id = ?", Long.class, tenantId);
        return Result.ok(new PageResult<>(
                jdbcTemplate.query("SELECT * FROM `role` WHERE tenant_id = ? ORDER BY id DESC LIMIT ? OFFSET ?", this::mapRole, tenantId, size, PageSupport.offset(page, size)),
                page,
                size,
                total == null ? 0 : total
        ));
    }

    @GetMapping("/{id}")
    public Result<RoleView> get(@PathVariable long id) {
        return Result.ok(load(id));
    }

    @PostMapping
    public Result<RoleView> create(@RequestBody RolePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        jdbcTemplate.update("""
                        INSERT INTO `role` (tenant_id, role_code, role_name, data_scope, status)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId,
                payload.code(),
                payload.name(),
                blankDefault(payload.dataScope(), "TENANT"),
                blankDefault(payload.status(), "ENABLED")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assignPermissions(id, payload.permissions());
        auditService.success("ROLE_CREATE", "role", id, Map.of("code", payload.code()), request);
        return Result.ok(load(id));
    }

    @PutMapping("/{id}")
    public Result<RoleView> update(@PathVariable long id, @RequestBody RolePayload payload, HttpServletRequest request) {
        ensureRole(id);
        jdbcTemplate.update("""
                        UPDATE `role`
                        SET role_code = ?, role_name = ?, data_scope = ?, status = ?
                        WHERE tenant_id = ? AND id = ?
                        """,
                payload.code(),
                payload.name(),
                blankDefault(payload.dataScope(), "TENANT"),
                blankDefault(payload.status(), "ENABLED"),
                SecurityContext.tenantId(),
                id
        );
        if (payload.permissions() != null) {
            assignPermissions(id, payload.permissions());
        }
        auditService.success("ROLE_UPDATE", "role", id, Map.of("code", payload.code()), request);
        return Result.ok(load(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        ensureRole(id);
        jdbcTemplate.update("DELETE FROM role_permission WHERE role_id = ?", id);
        jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", id);
        jdbcTemplate.update("DELETE FROM `role` WHERE tenant_id = ? AND id = ?", SecurityContext.tenantId(), id);
        auditService.success("ROLE_DELETE", "role", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PutMapping("/{id}/permissions")
    public Result<Void> permissions(@PathVariable long id, @RequestBody PermissionPayload payload, HttpServletRequest request) {
        ensureRole(id);
        assignPermissions(id, payload.permissions());
        auditService.success("ROLE_PERMISSIONS", "role", id, Map.of("permissions", payload.permissions()), request);
        return Result.ok();
    }

    private RoleView load(long id) {
        ensureRole(id);
        return jdbcTemplate.queryForObject("SELECT * FROM `role` WHERE tenant_id = ? AND id = ?", this::mapRole, SecurityContext.tenantId(), id);
    }

    private void ensureRole(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `role` WHERE tenant_id = ? AND id = ?", Integer.class, SecurityContext.tenantId(), id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "role not found");
        }
    }

    private void assignPermissions(long roleId, List<String> permissions) {
        if (permissions == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM role_permission WHERE role_id = ?", roleId);
        for (String permission : permissions) {
            Long permissionId = jdbcTemplate.query("SELECT id FROM permission WHERE perm_code = ? LIMIT 1",
                    rs -> rs.next() ? rs.getLong("id") : null,
                    permission);
            if (permissionId != null) {
                jdbcTemplate.update("INSERT IGNORE INTO role_permission (role_id, permission_id) VALUES (?, ?)", roleId, permissionId);
            }
        }
    }

    private RoleView mapRole(ResultSet rs, int rowNum) throws SQLException {
        long roleId = rs.getLong("id");
        List<String> permissions = jdbcTemplate.queryForList("""
                SELECT p.perm_code
                FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id
                WHERE rp.role_id = ?
                ORDER BY p.perm_code
                """, String.class, roleId);
        return new RoleView(
                roleId,
                rs.getLong("tenant_id"),
                rs.getString("role_name"),
                rs.getString("role_code"),
                rs.getString("data_scope"),
                rs.getString("status"),
                permissions,
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record RolePayload(@NotBlank String name, @NotBlank String code, String dataScope, String status, List<String> permissions) {
    }

    public record PermissionPayload(List<String> permissions) {
    }

    public record RoleView(Long id, Long tenantId, String name, String code, String dataScope, String status, List<String> permissions, String createdAt, String updatedAt) {
    }
}
