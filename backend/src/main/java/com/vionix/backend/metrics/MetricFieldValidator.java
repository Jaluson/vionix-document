package com.vionix.backend.metrics;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MetricFieldValidator {
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Set<String> AGGS = Set.of("mean", "sum", "max", "min", "count");
    private static final Set<String> LEVELS = Set.of("raw", "min", "hour", "day");

    public List<String> fields(String fields) {
        if (fields == null || fields.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "fields is required");
        }
        List<String> parsed = Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        if (parsed.isEmpty() || parsed.stream().anyMatch(field -> !FIELD.matcher(field).matches())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid metric fields");
        }
        return parsed;
    }

    public String agg(String agg) {
        String value = agg == null || agg.isBlank() ? "mean" : agg;
        if (!AGGS.contains(value)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid agg");
        }
        return value;
    }

    public String level(String level, String start, String end) {
        if (level != null && !level.isBlank()) {
            if (!LEVELS.contains(level)) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "invalid level");
            }
            return level;
        }
        if (start != null && start.matches("-?\\d+[smhd]")) {
            if (start.endsWith("s") || start.endsWith("m")) {
                return "raw";
            }
            if (start.endsWith("h")) {
                return "min";
            }
        }
        return "hour";
    }

    public void assertDeviceId(String deviceId) {
        if (deviceId != null && !deviceId.isBlank() && !FIELD.matcher(deviceId.replace("-", "_")).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid deviceId");
        }
    }

    public void assertSource(String source) {
        if (source != null && !source.isBlank() && !FIELD.matcher(source.replace("-", "_")).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid source");
        }
    }
}
