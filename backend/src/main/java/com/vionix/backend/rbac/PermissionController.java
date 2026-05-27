package com.vionix.backend.rbac;

import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.security.RequirePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
@RequirePermission("api:rbac:manage")
public class PermissionController {
    private final JdbcTemplate jdbcTemplate;

    public PermissionController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Result<List<PermissionView>> list() {
        return Result.ok(loadPermissions());
    }

    @GetMapping("/tree")
    public Result<List<PermissionView>> tree() {
        Map<String, PermissionView> roots = new LinkedHashMap<>();
        for (PermissionView permission : loadPermissions()) {
            roots.computeIfAbsent(permission.type(), type -> new PermissionView(null, type, type, type, null, new ArrayList<>()))
                    .children()
                    .add(permission);
        }
        return Result.ok(new ArrayList<>(roots.values()));
    }

    private List<PermissionView> loadPermissions() {
        return jdbcTemplate.query("""
                        SELECT id, perm_code, perm_type, path, description
                        FROM permission
                        ORDER BY perm_type, perm_code
                        """,
                (rs, rowNum) -> new PermissionView(
                        rs.getLong("id"),
                        rs.getString("perm_code"),
                        rs.getString("perm_code"),
                        rs.getString("perm_type"),
                        rs.getString("path"),
                        new ArrayList<>()
                )
        );
    }

    public record PermissionView(Long id, String code, String name, String type, String path, List<PermissionView> children) {
    }
}
