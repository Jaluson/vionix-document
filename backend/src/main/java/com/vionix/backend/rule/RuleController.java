package com.vionix.backend.rule;

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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
public class RuleController {
    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;
    private final DeviceAccessService deviceAccessService;
    private final AuditService auditService;

    public RuleController(
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
    @RequirePermission("api:rule:view")
    public Result<PageResult<RuleView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize
    ) {
        long tenantId = SecurityContext.tenantId();
        int page = PageSupport.pageNum(pageNum);
        int size = PageSupport.pageSize(pageSize);
        StringBuilder where = new StringBuilder("tenant_id = ?");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE ?");
            params.add("%" + keyword + "%");
        }
        if (scope != null && !scope.isBlank()) {
            where.append(" AND scope = ?");
            params.add(scope);
        }
        if (enabled != null) {
            where.append(" AND enabled = ?");
            params.add(enabled);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `rule` WHERE " + where, Long.class, params.toArray());
        params.add(size);
        params.add(PageSupport.offset(page, size));
        List<RuleView> rules = jdbcTemplate.query(
                "SELECT * FROM `rule` WHERE " + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                this::mapRule,
                params.toArray()
        );
        return Result.ok(new PageResult<>(rules, page, size, total == null ? 0 : total));
    }

    @GetMapping("/{id}")
    @RequirePermission("api:rule:view")
    public Result<RuleView> get(@PathVariable long id) {
        return Result.ok(load(SecurityContext.tenantId(), id));
    }

    @PostMapping
    @RequirePermission("api:rule:manage")
    public Result<RuleView> create(@RequestBody RulePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        validateTarget(tenantId, payload);
        jdbcTemplate.update("""
                        INSERT INTO `rule` (tenant_id, name, scope, target_id, severity, enabled, suppression_seconds, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId,
                payload.name(),
                payload.scope(),
                payload.targetId(),
                blankDefault(payload.severity(), "WARNING"),
                payload.enabled() == null || payload.enabled(),
                payload.suppressionSeconds() == null ? 300 : payload.suppressionSeconds(),
                SecurityContext.require().userId()
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceChildren(tenantId, id, payload.conditions(), payload.actions());
        auditService.success("RULE_CREATE", "rule", id, Map.of("name", payload.name()), request);
        return Result.ok(load(tenantId, id));
    }

    @PutMapping("/{id}")
    @RequirePermission("api:rule:manage")
    public Result<RuleView> update(@PathVariable long id, @RequestBody RulePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureRule(tenantId, id);
        validateTarget(tenantId, payload);
        jdbcTemplate.update("""
                        UPDATE `rule`
                        SET name = ?, scope = ?, target_id = ?, severity = ?, enabled = ?, suppression_seconds = ?
                        WHERE tenant_id = ? AND id = ?
                        """,
                payload.name(),
                payload.scope(),
                payload.targetId(),
                blankDefault(payload.severity(), "WARNING"),
                payload.enabled() == null || payload.enabled(),
                payload.suppressionSeconds() == null ? 300 : payload.suppressionSeconds(),
                tenantId,
                id
        );
        replaceChildren(tenantId, id, payload.conditions(), payload.actions());
        auditService.success("RULE_UPDATE", "rule", id, Map.of("name", payload.name()), request);
        return Result.ok(load(tenantId, id));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("api:rule:manage")
    public Result<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureRule(tenantId, id);
        jdbcTemplate.update("DELETE FROM rule_action WHERE tenant_id = ? AND rule_id = ?", tenantId, id);
        jdbcTemplate.update("DELETE FROM rule_condition WHERE tenant_id = ? AND rule_id = ?", tenantId, id);
        jdbcTemplate.update("DELETE FROM `rule` WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditService.success("RULE_DELETE", "rule", id, Map.of("id", id), request);
        return Result.ok();
    }

    @PutMapping("/{id}/toggle")
    @RequirePermission("api:rule:manage")
    public Result<RuleView> toggle(@PathVariable long id, @RequestBody TogglePayload payload, HttpServletRequest request) {
        long tenantId = SecurityContext.tenantId();
        ensureRule(tenantId, id);
        jdbcTemplate.update("UPDATE `rule` SET enabled = ? WHERE tenant_id = ? AND id = ?", payload.enabled(), tenantId, id);
        auditService.success("RULE_TOGGLE", "rule", id, Map.of("enabled", payload.enabled()), request);
        return Result.ok(load(tenantId, id));
    }

    private void validateTarget(long tenantId, RulePayload payload) {
        if ("DEVICE".equals(payload.scope())) {
            deviceAccessService.assertEnabledVisible(tenantId, payload.targetId());
        } else if ("GROUP".equals(payload.scope())) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM device_group WHERE tenant_id = ? AND id = ? AND status = 'ENABLED'",
                    Integer.class,
                    tenantId,
                    Long.parseLong(payload.targetId())
            );
            if (count == null || count == 0) {
                throw new ApiException(ErrorCode.NOT_FOUND, "device group not found or disabled");
            }
        }
    }

    private void replaceChildren(long tenantId, long ruleId, List<RuleConditionPayload> conditions, List<RuleActionPayload> actions) {
        jdbcTemplate.update("DELETE FROM rule_condition WHERE tenant_id = ? AND rule_id = ?", tenantId, ruleId);
        jdbcTemplate.update("DELETE FROM rule_action WHERE tenant_id = ? AND rule_id = ?", tenantId, ruleId);
        for (RuleConditionPayload condition : conditions == null ? List.<RuleConditionPayload>of() : conditions) {
            jdbcTemplate.update("""
                            INSERT INTO rule_condition
                              (tenant_id, rule_id, group_index, condition_type, metric, operator, threshold, duration_seconds)
                            VALUES (?, ?, ?, 'THRESHOLD', ?, ?, ?, ?)
                            """,
                    tenantId,
                    ruleId,
                    condition.groupIndex(),
                    condition.metric(),
                    condition.operator(),
                    BigDecimal.valueOf(condition.threshold()),
                    condition.durationSeconds()
            );
        }
        for (RuleActionPayload action : actions == null ? List.<RuleActionPayload>of() : actions) {
            jdbcTemplate.update("""
                            INSERT INTO rule_action (tenant_id, rule_id, action_type, config, enabled)
                            VALUES (?, ?, ?, ?, TRUE)
                            """,
                    tenantId,
                    ruleId,
                    action.actionType(),
                    jsonSupport.toJson(action.config())
            );
        }
    }

    private RuleView load(long tenantId, long id) {
        ensureRule(tenantId, id);
        return jdbcTemplate.queryForObject("SELECT * FROM `rule` WHERE tenant_id = ? AND id = ?", this::mapRule, tenantId, id);
    }

    private void ensureRule(long tenantId, long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `rule` WHERE tenant_id = ? AND id = ?", Integer.class, tenantId, id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "rule not found");
        }
    }

    private RuleView mapRule(ResultSet rs, int rowNum) throws SQLException {
        long tenantId = rs.getLong("tenant_id");
        long ruleId = rs.getLong("id");
        List<RuleConditionPayload> conditions = jdbcTemplate.query("""
                        SELECT * FROM rule_condition
                        WHERE tenant_id = ? AND rule_id = ?
                        ORDER BY group_index, id
                        """,
                (conditionRs, conditionRow) -> new RuleConditionPayload(
                        conditionRs.getInt("group_index"),
                        conditionRs.getString("condition_type"),
                        conditionRs.getString("metric"),
                        conditionRs.getString("operator"),
                        conditionRs.getBigDecimal("threshold").doubleValue(),
                        conditionRs.getInt("duration_seconds")
                ),
                tenantId,
                ruleId
        );
        List<RuleActionPayload> actions = jdbcTemplate.query("""
                        SELECT * FROM rule_action
                        WHERE tenant_id = ? AND rule_id = ?
                        ORDER BY id
                        """,
                (actionRs, actionRow) -> new RuleActionPayload(
                        actionRs.getString("action_type"),
                        jsonSupport.toMap(actionRs.getString("config"))
                ),
                tenantId,
                ruleId
        );
        return new RuleView(
                ruleId,
                tenantId,
                rs.getString("name"),
                rs.getString("scope"),
                rs.getString("target_id"),
                rs.getString("severity"),
                rs.getBoolean("enabled"),
                rs.getInt("suppression_seconds"),
                conditions,
                actions,
                String.valueOf(rs.getTimestamp("created_at").toInstant()),
                String.valueOf(rs.getTimestamp("updated_at").toInstant())
        );
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record RulePayload(
            @NotBlank String name,
            @NotBlank String scope,
            @NotBlank String targetId,
            String severity,
            Boolean enabled,
            Integer suppressionSeconds,
            List<RuleConditionPayload> conditions,
            List<RuleActionPayload> actions
    ) {
    }

    public record RuleConditionPayload(int groupIndex, String conditionType, String metric, String operator, double threshold, int durationSeconds) {
    }

    public record RuleActionPayload(String actionType, Map<String, Object> config) {
    }

    public record TogglePayload(boolean enabled) {
    }

    public record RuleView(
            Long id,
            Long tenantId,
            String name,
            String scope,
            String targetId,
            String severity,
            boolean enabled,
            int suppressionSeconds,
            List<RuleConditionPayload> conditions,
            List<RuleActionPayload> actions,
            String createdAt,
            String updatedAt
    ) {
    }
}
