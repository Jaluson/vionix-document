package com.vionix.backend.metrics;

import com.vionix.backend.common.api.Result;
import com.vionix.backend.common.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
@RequirePermission("api:metrics:view")
public class MetricsController {
    private final InfluxQueryService queryService;

    public MetricsController(InfluxQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Result<InfluxQueryService.MetricsResponse> query(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String measurement,
            @RequestParam String fields,
            @RequestParam String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String agg,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String source
    ) {
        return Result.ok(queryService.query(new InfluxQueryService.MetricsRequest(
                level,
                measurement,
                fields,
                start,
                end,
                agg,
                deviceId,
                source
        )));
    }
}
