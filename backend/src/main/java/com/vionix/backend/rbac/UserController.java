package com.vionix.backend.rbac;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.audit.AuditService;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.Passwords;
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
@RequestMapping("/api/users")
@RequirePermission("api:rbac:manage")
public class UserController {
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public UserController(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<PageResult<UserView>> list(@RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user` WHERE tenant_id = ?", Long.class, tenantId);
        return Result.ok(new PageResult<>(
                jdbcTemplate.query("SELECT * FROM `user` WHERE tenant_id = ? ORDER BY id DESC LIMIT ? OFFSET ?", this::mapUser, tenantId, size, PageSupport.offset(page, size)),
                page,
                size,
                total == null ? 0 : total
        ));
    }

    @GetMapping("/{id}")
    public Result<UserView> get(@PathVariable long id) {
        return Result.ok(load(id));
    }

    @PostMapping
    public Result<UserView> create(@RequestBody UserPayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        Passwords.PasswordHash hash = Passwords.hashPassword(payload.password());
        jdbcTemplate.update("""
                        INSERT INTO `user` (tenant_id, username, password, password_salt, nickname, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                tenantId,
                payload.username(),
                hash.hash(),
                hash.salt(),
                payload.nickname(),
                blankDefault(payload.status(), "ENABLED")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assignRoles(id, payload.roles());
        auditService.success("USER_CREATE", "user", id, Map.of("username", payload.username()), request);
        return Result.ok(load(id));
    }

    @PutMapping("/{id}")
    public Result<UserView> update(@PathVariable long id, @RequestBody UserPayload payload, HttpServletRequest request) {
        ensureUser(id);
        jdbcTemplate.update("UPDATE `user` SET username = ?, nickname = ?, status = ? WHERE tenant_id = ? AND id = ?",
                payload.username(), payload.nickname(), blankDefault(payload.status(), "ENABLED"), SecurityContext.tenantId(), id);
        if (payload.roles() != null) {
            assignRoles(id, payload.roles());
        }
        auditService.success("USER_UPDATE", "user", id, Map.of("username", payload.username()), request);
        return Result.ok(load(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        ensureUser(id);
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM `user` WHERE tenant_id = ? AND id = ?", SecurityContext.tenantId(), id);
        auditService.success("USER_DELETE", "user", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    public Result<Void> password(@PathVariable long id, @RequestBody PasswordPayload payload, HttpServletRequest request) {
        ensureUser(id);
        Passwords.PasswordHash hash = Passwords.hashPassword(payload.password());
        jdbcTemplate.update("UPDATE `user` SET password = ?, password_salt = ? WHERE tenant_id = ? AND id = ?",
                hash.hash(), hash.salt(), SecurityContext.tenantId(), id);
        auditService.success("USER_PASSWORD", "user", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PutMapping("/{id}/roles")
    public Result<Void> roles(@PathVariable long id, @RequestBody RolesPayload payload, HttpServletRequest request) {
        ensureUser(id);
        assignRoles(id, payload.roles());
        auditService.success("USER_ROLES", "user", id, Map.of("roles", payload.roles()), request);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    public Result<UserView> status(@PathVariable long id, @RequestBody StatusPayload payload, HttpServletRequest request) {
        ensureUser(id);
        jdbcTemplate.update("UPDATE `user` SET status = ? WHERE tenant_id = ? AND id = ?", payload.status(), SecurityContext.tenantId(), id);
        auditService.success("USER_STATUS", "user", id, Map.of("status", payload.status()), request);
        return Result.ok(load(id));
    }

    private UserView load(long id) {
        ensureUser(id);
        return jdbcTemplate.queryForObject("SELECT * FROM `user` WHERE tenant_id = ? AND id = ?", this::mapUser, SecurityContext.tenantId(), id);
    }

    private void ensureUser(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user` WHERE tenant_id = ? AND id = ?", Integer.class, SecurityContext.tenantId(), id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "user not found");
        }
    }

    private void assignRoles(Long userId, List<String> roles) {
        if (roles == null) {
            return;
        }
        long tenantId = SecurityContext.tenantId();
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
        for (String role : roles) {
            Long roleId = jdbcTemplate.query("""
                            SELECT id FROM `role`
                            WHERE tenant_id = ? AND (role_code = ? OR CAST(id AS CHAR) = ?)
                            LIMIT 1
                            """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    tenantId,
                    role,
                    role
            );
            if (roleId != null) {
                jdbcTemplate.update("INSERT IGNORE INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
            }
        }
    }

    private UserView mapUser(ResultSet rs, int rowNum) throws SQLException {
        long userId = rs.getLong("id");
        List<String> roles = jdbcTemplate.queryForList("""
                SELECT r.role_code
                FROM user_role ur
                JOIN `role` r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                ORDER BY r.role_code
                """, String.class, userId);
        return new UserView(
                userId,
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("nickname"),
                rs.getString("status"),
                roles,
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record UserPayload(@NotBlank String username, @NotBlank String password, String nickname, String status, List<String> roles) {
    }

    public record PasswordPayload(@NotBlank String password) {
    }

    public record RolesPayload(List<String> roles) {
    }

    public record StatusPayload(@NotBlank String status) {
    }

    public record UserView(Long id, Long tenantId, String username, String nickname, String status, List<String> roles, String createdAt, String updatedAt) {
    }
}
