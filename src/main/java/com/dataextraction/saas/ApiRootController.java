package com.dataextraction.saas;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiRootController {
    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "ExtractIQ Spring Boot API",
                "status", "UP",
                "apiBasePath", "/api/v1",
                "healthUrl", "/actuator/health",
                "frontendUrl", "http://localhost:4200");
    }
}
