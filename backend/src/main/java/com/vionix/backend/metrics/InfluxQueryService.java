package com.vionix.backend.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.common.exception.ApiException;
import com.vionix.backend.common.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class InfluxQueryService {
    private final VionixProperties properties;
    private final ObjectMapper objectMapper;
    private final MetricFieldValidator validator;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public InfluxQueryService(
            VionixProperties properties,
            ObjectMapper objectMapper,
            MetricFieldValidator validator,
            JdbcTemplate jdbcTemplate
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MetricsResponse query(MetricsRequest request) {
        long tenantId = SecurityContext.tenantId();
        validator.assertDeviceId(request.deviceId());
        validator.assertSource(request.source());
        if (request.deviceId() != null && !request.deviceId().isBlank()) {
            assertVisibleDevice(tenantId, request.deviceId());
        }
        List<String> fields = validator.fields(request.fields());
        String agg = validator.agg(request.agg());
        String level = validator.level(request.level(), request.start(), request.end());
        String bucket = bucket(level);
        String flux = buildFlux(tenantId, bucket, level, fields, agg, request);
        List<MetricSeries> series = executeFlux(level, agg, request.deviceId(), fields, flux);
        return new MetricsResponse(level, bucket, agg, series);
    }

    private void assertVisibleDevice(long tenantId, String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE tenant_id = ? AND device_id = ? AND status = 'ENABLED'",
                Integer.class,
                tenantId,
                deviceId
        );
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.FORBIDDEN, "device is not visible or enabled");
        }
    }

    private String bucket(String level) {
        return switch (level) {
            case "raw" -> "device_raw";
            case "min" -> "device_min";
            case "hour" -> "device_hour";
            case "day" -> "device_day";
            default -> throw new ApiException(ErrorCode.BAD_REQUEST, "invalid level");
        };
    }

    private String buildFlux(long tenantId, String bucket, String level, List<String> fields, String agg, MetricsRequest request) {
        StringJoiner fieldFilter = new StringJoiner(" or ");
        for (String field : fields) {
            String fieldName = "raw".equals(level) ? field : field + "_" + agg;
            fieldFilter.add("r._field == \"" + fieldName + "\"");
        }
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(bucket).append("\")\n");
        flux.append("  |> range(start: ").append(safeTime(request.start())).append(", stop: ").append(safeStop(request.end())).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"").append(measurement(request.measurement())).append("\")\n");
        flux.append("    and r.tenant_id == \"").append(tenantId).append("\"\n");
        flux.append("    and (").append(fieldFilter).append("))\n");
        if (request.deviceId() != null && !request.deviceId().isBlank()) {
            flux.append("  |> filter(fn: (r) => r.device_id == \"").append(request.deviceId()).append("\")\n");
        }
        if (request.source() != null && !request.source().isBlank()) {
            flux.append("  |> filter(fn: (r) => r.source == \"").append(request.source()).append("\")\n");
        }
        flux.append("  |> sort(columns: [\"_time\"])\n");
        return flux.toString();
    }

    private String measurement(String measurement) {
        if (measurement == null || measurement.isBlank()) {
            return "device_metrics";
        }
        if (!measurement.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "invalid measurement");
        }
        return measurement;
    }

    private String safeTime(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "start is required");
        }
        if (value.matches("-?\\d+[smhd]") || value.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return value;
        }
        throw new ApiException(ErrorCode.BAD_REQUEST, "invalid start");
    }

    private String safeStop(String value) {
        if (value == null || value.isBlank()) {
            return "now()";
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return value;
        }
        throw new ApiException(ErrorCode.BAD_REQUEST, "invalid end");
    }

    private List<MetricSeries> executeFlux(String level, String agg, String deviceId, List<String> requestedFields, String flux) {
        if (properties.getInfluxdb().getToken() == null || properties.getInfluxdb().getToken().isBlank()) {
            return requestedFields.stream()
                    .map(field -> new MetricSeries(field, agg, deviceId, List.of()))
                    .toList();
        }
        try {
            Map<String, String> payload = Map.of("query", flux, "type", "flux");
            URI uri = properties.getInfluxdb().getUrl()
                    .resolve("/api/v2/query?org=" + URLEncoder.encode(properties.getInfluxdb().getOrg(), StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Token " + properties.getInfluxdb().getToken())
                    .header("Accept", "application/csv")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "InfluxDB query failed");
            }
            return parseCsv(level, agg, deviceId, response.body());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "InfluxDB query failed");
        }
    }

    private List<MetricSeries> parseCsv(String level, String agg, String defaultDeviceId, String csv) {
        Map<String, List<MetricValue>> valuesByField = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] lines = csv.split("\\R");
        String[] header = null;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split(",", -1);
            if (header == null) {
                header = columns;
                continue;
            }
            Map<String, String> row = row(header, columns);
            String field = row.get("_field");
            if (field == null || field.isBlank()) {
                continue;
            }
            if (!"raw".equals(level) && field.endsWith("_" + agg)) {
                field = field.substring(0, field.length() - agg.length() - 1);
            }
            String value = row.get("_value");
            String time = row.get("_time");
            if (value == null || time == null || value.isBlank() || time.isBlank()) {
                continue;
            }
            valuesByField.computeIfAbsent(field, ignored -> new ArrayList<>())
                    .add(new MetricValue(time, Double.parseDouble(value), row.getOrDefault("device_id", defaultDeviceId)));
        }
        return valuesByField.entrySet().stream()
                .map(entry -> new MetricSeries(entry.getKey(), agg, defaultDeviceId, entry.getValue()))
                .toList();
    }

    private Map<String, String> row(String[] header, String[] columns) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.length && i < columns.length; i++) {
            row.put(header[i], columns[i]);
        }
        return row;
    }

    public record MetricsRequest(
            String level,
            String measurement,
            String fields,
            String start,
            String end,
            String agg,
            String deviceId,
            String source
    ) {
    }

    public record MetricsResponse(String level, String bucket, String agg, List<MetricSeries> series) {
    }

    public record MetricSeries(String field, String agg, String deviceId, List<MetricValue> data) {
    }

    public record MetricValue(String time, double value, String deviceId) {
    }
}
