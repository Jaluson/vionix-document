package com.vionix.backend.common.audit;

import com.vionix.backend.common.jdbc.JsonSupport;
import com.vionix.backend.common.security.SecurityContext;
import com.vionix.backend.common.trace.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final JsonSupport jsonSupport;

    public AuditService(JdbcTemplate jdbcTemplate, JsonSupport jsonSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = jsonSupport;
    }

    public void success(String action, String resourceType, Object resourceId, Map<String, Object> summary, HttpServletRequest request) {
        write(action, resourceType, resourceId, summary, "SUCCESS", request);
    }

    public void failure(String action, String resourceType, Object resourceId, Map<String, Object> summary, HttpServletRequest request) {
        write(action, resourceType, resourceId, summary, "FAILURE", request);
    }

    private void write(String action, String resourceType, Object resourceId, Map<String, Object> summary, String result, HttpServletRequest request) {
        long tenantId = SecurityContext.current().map(principal -> principal.tenantId()).orElse(0L);
        Long userId = SecurityContext.current().map(principal -> principal.userId()).orElse(null);
        String ip = request == null ? null : request.getRemoteAddr();
        try {
            jdbcTemplate.update("""
                            INSERT INTO audit_log
                              (tenant_id, user_id, action, resource_type, resource_id, request_summary, result, ip_address, trace_id)
                            VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                            """,
                    tenantId,
                    userId,
                    action,
                    resourceType,
                    resourceId == null ? null : String.valueOf(resourceId),
                    jsonSupport.toJson(summary),
                    result,
                    ip,
                    TraceIds.current()
            );
        } catch (DataAccessException exception) {
            log.warn("Unable to write audit log action={} resourceType={} resourceId={}", action, resourceType, resourceId, exception);
        }
    }
}
