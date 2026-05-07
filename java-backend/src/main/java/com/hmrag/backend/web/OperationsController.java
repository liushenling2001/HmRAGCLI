package com.hmrag.backend.web;

import com.hmrag.backend.service.DataSourceService;
import com.hmrag.backend.web.dto.ApiDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

    private final DataSourceService dataSourceService;

    public OperationsController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/dashboard")
    public ApiDtos.OperationsDashboard dashboard() {
        return dataSourceService.getDashboard();
    }

    @GetMapping("/jobs")
    public ApiDtos.PageResponse<ApiDtos.OperationsJobItem> jobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return dataSourceService.listJobs(page, pageSize);
    }

    @GetMapping("/failures")
    public ApiDtos.PageResponse<ApiDtos.FailureItem> failures(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return dataSourceService.listFailures(page, pageSize);
    }

    @PostMapping("/failures/cleanup-temp-files")
    public ApiDtos.TempFilesCleanupResult cleanupTempFailures() {
        return dataSourceService.cleanupTemporaryFailures();
    }
}
