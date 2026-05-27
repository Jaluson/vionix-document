package com.vionix.backend.common.web;

import com.vionix.backend.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/ping")
    public Result<Map<String, String>> ping() {
        return Result.ok(Map.of(
                "service", "vionix-backend",
                "status", "UP"
        ));
    }
}
