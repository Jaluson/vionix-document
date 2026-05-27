package com.vionix.backend.auth;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.AuthPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PrincipalLoader {
    private final JdbcTemplate jdbcTemplate;

    public PrincipalLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AuthPrincipal loadByUserId(long userId) {
        return jdbcTemplate.query("""
                        SELECT u.id,
                               u.tenant_id,
                               u.username,
                               COALESCE(GROUP_CONCAT(DISTINCT r.role_code), '') AS roles,
                               COALESCE(GROUP_CONCAT(DISTINCT p.perm_code), '') AS permissions,
                               COALESCE(MAX(r.data_scope), 'SELF') AS data_scope
                        FROM `user` u
                        LEFT JOIN user_role ur ON ur.user_id = u.id
                        LEFT JOIN `role` r ON r.id = ur.role_id AND r.status = 'ENABLED'
                        LEFT JOIN role_permission rp ON rp.role_id = r.id
                        LEFT JOIN permission p ON p.id = rp.permission_id
                        WHERE u.id = ? AND u.status = 'ENABLED'
                        GROUP BY u.id, u.tenant_id, u.username
                        """,
                rs -> {
                    if (!rs.next()) {
                        throw new ApiException(ErrorCode.UNAUTHORIZED, "user is disabled or not found");
                    }
                    return new AuthPrincipal(
                            rs.getLong("id"),
                            rs.getLong("tenant_id"),
                            rs.getString("username"),
                            splitCsv(rs.getString("roles")),
                            splitCsv(rs.getString("permissions")),
                            rs.getString("data_scope")
                    );
                },
                userId
        );
    }

    private Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
