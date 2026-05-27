package com.vionix.backend.alert;

import java.time.Instant;

public record AlertEvent(
        long alertId,
        long tenantId,
        long ruleId,
        String ruleName,
        String severity,
        String deviceId,
        String metric,
        double triggerValue,
        Instant firedAt
) {
}
