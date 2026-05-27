package com.vionix.backend.metrics;

import java.time.Instant;
import java.util.Map;

public record MetricPoint(
        long tenantId,
        String deviceId,
        String source,
        Instant time,
        Map<String, Double> metrics
) {
}
