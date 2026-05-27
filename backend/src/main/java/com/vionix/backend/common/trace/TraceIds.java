package com.vionix.backend.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIds {
    private TraceIds() {
    }

    public static String current() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
