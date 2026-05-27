package com.vionix.backend.rule;

import com.vionix.backend.alert.AlertEvent;
import com.vionix.backend.common.jdbc.JsonSupport;
import com.vionix.backend.metrics.MetricPoint;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RuleEngine {
    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;
    private final ApplicationEventPublisher eventPublisher;

    public RuleEngine(JdbcTemplate jdbcTemplate, JsonSupport jsonSupport, ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void evaluate(MetricPoint point) {
        if (!deviceEnabled(point.tenantId(), point.deviceId())) {
            return;
        }
        for (RuleRuntime rule : loadRules(point)) {
            EvaluationResult result = evaluateRule(rule, point.metrics());
            if (!result.matched()) {
                continue;
            }
            if (suppressed(point.tenantId(), rule.id(), point.deviceId(), rule.suppressionSeconds())) {
                continue;
            }
            long alertId = createAlert(point, rule, result);
            logActions(point.tenantId(), alertId, rule.id());
            eventPublisher.publishEvent(new AlertEvent(
                    alertId,
                    point.tenantId(),
                    rule.id(),
                    rule.name(),
                    rule.severity(),
                    point.deviceId(),
                    result.metric(),
                    result.value(),
                    point.time()
            ));
        }
    }

    private boolean deviceEnabled(long tenantId, String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE tenant_id = ? AND device_id = ? AND status = 'ENABLED'",
                Integer.class,
                tenantId,
                deviceId
        );
        return count != null && count > 0;
    }

    private List<RuleRuntime> loadRules(MetricPoint point) {
        return jdbcTemplate.query("""
                        SELECT DISTINCT r.*
                        FROM `rule` r
                        LEFT JOIN device_group_member gm
                          ON gm.tenant_id = r.tenant_id
                         AND r.scope = 'GROUP'
                         AND gm.group_id = CAST(r.target_id AS UNSIGNED)
                        WHERE r.tenant_id = ?
                          AND r.enabled = TRUE
                          AND (
                            r.scope = 'TENANT'
                            OR (r.scope = 'DEVICE' AND r.target_id = ?)
                            OR (r.scope = 'GROUP' AND gm.device_id = ?)
                          )
                        """,
                this::mapRule,
                point.tenantId(),
                point.deviceId(),
                point.deviceId()
        );
    }

    private EvaluationResult evaluateRule(RuleRuntime rule, Map<String, Double> metrics) {
        Map<Integer, List<ConditionRuntime>> groups = rule.conditions().stream()
                .sorted(Comparator.comparing(ConditionRuntime::groupIndex))
                .collect(Collectors.groupingBy(ConditionRuntime::groupIndex, LinkedHashMap::new, Collectors.toList()));
        for (List<ConditionRuntime> group : groups.values()) {
            boolean matched = true;
            EvaluationResult first = null;
            for (ConditionRuntime condition : group) {
                Double value = metrics.get(condition.metric());
                if (value == null || !compare(value, condition.operator(), condition.threshold())) {
                    matched = false;
                    break;
                }
                if (first == null) {
                    first = new EvaluationResult(true, condition.metric(), value);
                }
            }
            if (matched && first != null) {
                return first;
            }
        }
        return new EvaluationResult(false, null, 0);
    }

    private boolean compare(double value, String operator, double threshold) {
        return switch (operator) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "=" -> Double.compare(value, threshold) == 0;
            case "!=" -> Double.compare(value, threshold) != 0;
            default -> false;
        };
    }

    private boolean suppressed(long tenantId, long ruleId, String deviceId, int suppressionSeconds) {
        LocalDateTime since = LocalDateTime.ofInstant(Instant.now().minusSeconds(suppressionSeconds), ZoneOffset.UTC);
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM alert
                        WHERE tenant_id = ?
                          AND rule_id = ?
                          AND device_id = ?
                          AND status = 'FIRING'
                          AND fired_at >= ?
                        """,
                Integer.class,
                tenantId,
                ruleId,
                deviceId,
                since
        );
        return count != null && count > 0;
    }

    private long createAlert(MetricPoint point, RuleRuntime rule, EvaluationResult result) {
        jdbcTemplate.update("""
                        INSERT INTO alert (tenant_id, rule_id, device_id, severity, status, trigger_value, fired_at)
                        VALUES (?, ?, ?, ?, 'FIRING', ?, ?)
                        """,
                point.tenantId(),
                rule.id(),
                point.deviceId(),
                rule.severity(),
                BigDecimal.valueOf(result.value()),
                Timestamp.from(point.time())
        );
        Long alertId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return alertId == null ? 0 : alertId;
    }

    private void logActions(long tenantId, long alertId, long ruleId) {
        List<ActionRuntime> actions = jdbcTemplate.query("""
                        SELECT action_type, config
                        FROM rule_action
                        WHERE tenant_id = ? AND rule_id = ? AND enabled = TRUE
                        """,
                (rs, rowNum) -> new ActionRuntime(rs.getString("action_type"), jsonSupport.toMap(rs.getString("config"))),
                tenantId,
                ruleId
        );
        for (ActionRuntime action : actions) {
            jdbcTemplate.update("""
                            INSERT INTO alert_action_log (tenant_id, alert_id, action_type, success, request_summary)
                            VALUES (?, ?, ?, TRUE, ?)
                            """,
                    tenantId,
                    alertId,
                    action.actionType(),
                    jsonSupport.toJson(Map.of("config", action.config(), "dispatched", false))
            );
        }
    }

    private RuleRuntime mapRule(ResultSet rs, int rowNum) throws SQLException {
        long tenantId = rs.getLong("tenant_id");
        long ruleId = rs.getLong("id");
        List<ConditionRuntime> conditions = jdbcTemplate.query("""
                        SELECT group_index, metric, operator, threshold, duration_seconds
                        FROM rule_condition
                        WHERE tenant_id = ? AND rule_id = ?
                        ORDER BY group_index, id
                        """,
                (conditionRs, conditionRow) -> new ConditionRuntime(
                        conditionRs.getInt("group_index"),
                        conditionRs.getString("metric"),
                        conditionRs.getString("operator"),
                        conditionRs.getBigDecimal("threshold").doubleValue(),
                        conditionRs.getInt("duration_seconds")
                ),
                tenantId,
                ruleId
        );
        return new RuleRuntime(
                ruleId,
                rs.getString("name"),
                rs.getString("severity"),
                rs.getInt("suppression_seconds"),
                conditions
        );
    }

    private record RuleRuntime(long id, String name, String severity, int suppressionSeconds, List<ConditionRuntime> conditions) {
    }

    private record ConditionRuntime(int groupIndex, String metric, String operator, double threshold, int durationSeconds) {
    }

    private record EvaluationResult(boolean matched, String metric, double value) {
    }

    private record ActionRuntime(String actionType, Map<String, Object> config) {
    }
}
