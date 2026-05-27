package com.vionix.backend.alert;

import com.vionix.backend.common.api.PageResult;
import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.jdbc.PageSupport;
import com.vionix.backend.common.security.RequirePermission;
import com.vionix.backend.common.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequirePermission("api:alert:view")
public class AlertController {
    private final JdbcTemplate jdbcTemplate;

    public AlertController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Result<PageResult<AlertView>> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        QueryParts parts = queryParts(severity, status, deviceId, startTime, endTime);
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert a WHERE " + parts.where(), Long.class, parts.params().toArray());
        parts.params().add(size);
        parts.params().add(PageSupport.offset(page, size));
        List<AlertView> items = jdbcTemplate.query("""
                        SELECT a.*, r.name AS rule_name
                        FROM alert a
                        LEFT JOIN `rule` r ON r.tenant_id = a.tenant_id AND r.id = a.rule_id
                        WHERE %s
                        ORDER BY a.fired_at DESC
                        LIMIT ? OFFSET ?
                        """.formatted(parts.where()),
                this::mapAlert,
                parts.params().toArray()
        );
        return Result.ok(new PageResult<>(items, page, size, total == null ? 0 : total));
    }

    @GetMapping("/firing")
    public Result<List<AlertView>> firing() {
        return Result.ok(jdbcTemplate.query("""
                        SELECT a.*, r.name AS rule_name
                        FROM alert a
                        LEFT JOIN `rule` r ON r.tenant_id = a.tenant_id AND r.id = a.rule_id
                        WHERE a.tenant_id = ? AND a.status = 'FIRING'
                        ORDER BY a.fired_at DESC
                        LIMIT 100
                        """,
                this::mapAlert,
                SecurityContext.tenantId()
        ));
    }

    @GetMapping("/stats")
    public Result<Map<String, Long>> stats(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        QueryParts parts = queryParts(severity, status, deviceId, startTime, endTime);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert a WHERE " + parts.where(), Long.class, parts.params().toArray());
        Long firing = countBy("status", "FIRING", parts);
        Long resolved = countBy("status", "RESOLVED", parts);
        Long critical = countBy("severity", "CRITICAL", parts);
        Long warning = countBy("severity", "WARNING", parts);
        return Result.ok(Map.of(
                "total", total == null ? 0 : total,
                "firing", firing == null ? 0 : firing,
                "resolved", resolved == null ? 0 : resolved,
                "critical", critical == null ? 0 : critical,
                "warning", warning == null ? 0 : warning
        ));
    }

    private Long countBy(String column, String value, QueryParts base) {
        ArrayList<Object> params = new ArrayList<>(base.params());
        params.add(value);
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert a WHERE " + base.where() + " AND a." + column + " = ?", Long.class, params.toArray());
    }

    private QueryParts queryParts(String severity, String status, String deviceId, String startTime, String endTime) {
        ArrayList<Object> params = new ArrayList<>();
        params.add(SecurityContext.tenantId());
        StringBuilder where = new StringBuilder("a.tenant_id = ?");
        if (severity != null && !severity.isBlank()) {
            where.append(" AND a.severity = ?");
            params.add(severity);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            params.add(status);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            where.append(" AND a.device_id = ?");
            params.add(deviceId);
        }
        if (startTime != null && !startTime.isBlank()) {
            where.append(" AND a.fired_at >= ?");
            params.add(startTime.replace("T", " ").replace("Z", ""));
        }
        if (endTime != null && !endTime.isBlank()) {
            where.append(" AND a.fired_at <= ?");
            params.add(endTime.replace("T", " ").replace("Z", ""));
        }
        return new QueryParts(where.toString(), params);
    }

    private AlertView mapAlert(ResultSet rs, int rowNum) throws SQLException {
        return new AlertView(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getString("rule_name"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("device_id"),
                rs.getBigDecimal("trigger_value") == null ? null : rs.getBigDecimal("trigger_value").doubleValue(),
                String.valueOf(rs.getTimestamp("fired_at").toInstant()),
                rs.getTimestamp("resolved_at") == null ? null : String.valueOf(rs.getTimestamp("resolved_at").toInstant())
        );
    }

    private record QueryParts(String where, ArrayList<Object> params) {
    }

    public record AlertView(
            Long id,
            Long ruleId,
            String ruleName,
            String severity,
            String status,
            String deviceId,
            Double triggerValue,
            String firedAt,
            String resolvedAt
    ) {
    }
}
