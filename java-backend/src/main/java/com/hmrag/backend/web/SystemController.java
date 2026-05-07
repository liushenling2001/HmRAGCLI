package com.hmrag.backend.web;

import com.hmrag.backend.service.SystemHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SystemController {

    private final SystemHealthService systemHealthService;

    public SystemController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    @GetMapping({"/health", "/api/v1/system/health"})
    public Map<String, Object> health() {
        return systemHealthService.health();
    }
}
