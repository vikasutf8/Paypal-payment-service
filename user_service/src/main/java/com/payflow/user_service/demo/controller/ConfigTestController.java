package com.payflow.user_service.demo.controller;



import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigTestController {

    @Value("${environment.name:UNKNOWN}")
    private String environmentName;

    @Value("${app.name:UNKNOWN}")
    private String appName;

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of(
                "appName", appName,
                "environment", environmentName
        );
    }
}