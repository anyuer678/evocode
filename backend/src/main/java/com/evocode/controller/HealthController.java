package com.evocode.controller;

import com.evocode.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @Value("${spring.application.name}")
    private String appName;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", appName);
        body.put("status", "UP");
        body.put("version", "0.1.0-SNAPSHOT");
        body.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return Result.ok(body);
    }
}
