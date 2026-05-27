package com.vionix.backend.auth;

import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.common.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BootstrapData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapData.class);

    private final JdbcTemplate jdbcTemplate;
    private final VionixProperties properties;
    private final TokenStateService tokenStateService;

    public BootstrapData(JdbcTemplate jdbcTemplate, VionixProperties properties, TokenStateService tokenStateService) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.tokenStateService = tokenStateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedTenant();
            seedPermissions();
            long roleId = seedSuperAdminRole();
            seedAdminUser(roleId);
            tokenStateService.pruneExpiredRefreshTokens();
        } catch (DataAccessException exception) {
            log.warn("Bootstrap data skipped; database is not ready yet.");
        }
    }

    private void seedTenant() {
        jdbcTemplate.update("""
                INSERT INTO tenant (id, name, code, status)
                VALUES (1, 'Default Tenant', 'default', 'ENABLED')
                ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status)
                """);
    }

    private void seedPermissions() {
        for (PermissionSeed seed : permissions()) {
            jdbcTemplate.update("""
                            INSERT INTO permission (perm_code, perm_type, path, description)
                            VALUES (?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE perm_type = VALUES(perm_type), path = VALUES(path), description = VALUES(description)
                            """,
                    seed.code(),
                    seed.type(),
                    seed.path(),
                    seed.description()
            );
        }
    }

    private long seedSuperAdminRole() {
        jdbcTemplate.update("""
                INSERT INTO `role` (tenant_id, role_code, role_name, data_scope, status)
                VALUES (1, 'SUPER_ADMIN', 'Super Administrator', 'ALL', 'ENABLED')
                ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), data_scope = VALUES(data_scope), status = VALUES(status)
                """);
        long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM `role` WHERE tenant_id = 1 AND role_code = 'SUPER_ADMIN'",
                Long.class
        );
        jdbcTemplate.update("""
                INSERT IGNORE INTO role_permission (role_id, permission_id)
                SELECT ?, id FROM permission
                """, roleId);
        return roleId;
    }

    private void seedAdminUser(long roleId) {
        String password = properties.getSecurity().getBootstrapAdminPassword();
        if (password == null || password.isBlank()) {
            log.info("VIONIX_BOOTSTRAP_ADMIN_PASSWORD is empty; default admin user was not created.");
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE tenant_id = 1 AND username = 'admin'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        Passwords.PasswordHash hash = Passwords.hashPassword(password);
        jdbcTemplate.update("""
                        INSERT INTO `user` (tenant_id, username, password, password_salt, nickname, status)
                        VALUES (1, 'admin', ?, ?, 'Administrator', 'ENABLED')
                        """,
                hash.hash(),
                hash.salt()
        );
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE tenant_id = 1 AND username = 'admin'",
                Long.class
        );
        jdbcTemplate.update("INSERT IGNORE INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        log.info("Default admin user created for tenant code 'default'.");
    }

    private List<PermissionSeed> permissions() {
        return List.of(
                new PermissionSeed("menu:monitor", "MENU", "/monitor", "Realtime monitor menu"),
                new PermissionSeed("menu:device", "MENU", "/devices", "Device catalog menu"),
                new PermissionSeed("menu:device-group", "MENU", "/device-groups", "Device group menu"),
                new PermissionSeed("menu:rule", "MENU", "/rules", "Rule management menu"),
                new PermissionSeed("menu:alert", "MENU", "/alerts", "Alert center menu"),
                new PermissionSeed("menu:dashboard", "MENU", "/dashboards", "Dashboard menu"),
                new PermissionSeed("menu:user", "MENU", "/rbac/users", "User management menu"),
                new PermissionSeed("menu:role", "MENU", "/rbac/roles", "Role management menu"),
                new PermissionSeed("api:device:view", "API", "/api/devices", "View devices"),
                new PermissionSeed("api:device:manage", "API", "/api/devices", "Manage devices"),
                new PermissionSeed("api:metrics:view", "API", "/api/metrics", "View metrics"),
                new PermissionSeed("api:ws:subscribe", "API", "/ws", "Subscribe websocket data"),
                new PermissionSeed("api:rule:view", "API", "/api/rules", "View rules"),
                new PermissionSeed("api:rule:manage", "API", "/api/rules", "Manage rules"),
                new PermissionSeed("api:alert:view", "API", "/api/alerts", "View alerts"),
                new PermissionSeed("api:dashboard:view", "API", "/api/dashboards", "View dashboards"),
                new PermissionSeed("api:dashboard:manage", "API", "/api/dashboards", "Manage dashboards"),
                new PermissionSeed("api:tenant:manage", "API", "/api/tenants", "Manage tenants"),
                new PermissionSeed("api:rbac:manage", "API", "/api/users", "Manage users and roles"),
                new PermissionSeed("data:device:self", "DATA", null, "Own devices"),
                new PermissionSeed("data:device:group", "DATA", null, "Group devices"),
                new PermissionSeed("data:device:tenant", "DATA", null, "Tenant devices")
        );
    }

    private record PermissionSeed(String code, String type, String path, String description) {
    }
}
