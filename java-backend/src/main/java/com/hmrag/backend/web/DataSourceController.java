package com.hmrag.backend.web;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.service.DataSourceService;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/api/v1/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final AsyncTaskExecutor maintenanceTaskExecutor;
    private final AppProperties appProperties;

    public DataSourceController(
            DataSourceService dataSourceService,
            @Qualifier("maintenanceTaskExecutor") AsyncTaskExecutor maintenanceTaskExecutor,
            AppProperties appProperties
    ) {
        this.dataSourceService = dataSourceService;
        this.maintenanceTaskExecutor = maintenanceTaskExecutor;
        this.appProperties = appProperties;
    }

    @PostMapping
    public ApiDtos.DataSourceItem create(@Valid @RequestBody ApiDtos.CreateDataSourceRequest request) {
        return dataSourceService.create(request);
    }

    @GetMapping
    public List<ApiDtos.DataSourceItem> list() {
        return dataSourceService.list();
    }

    @GetMapping("/{id}")
    public ApiDtos.DataSourceItem get(@PathVariable UUID id) {
        return dataSourceService.get(id);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public WebAsyncTask<Void> delete(@PathVariable UUID id) {
        return maintenanceTask(() -> {
            dataSourceService.deleteDataSource(id);
            return null;
        });
    }

    @PostMapping("/{id}/scan")
    public ApiDtos.JobItem scan(@PathVariable UUID id, @RequestBody(required = false) ApiDtos.StartScanRequest request) {
        return dataSourceService.startScan(id, request != null && request.forceRescan());
    }

    @PostMapping("/{id}/ingest")
    public ApiDtos.JobItem ingest(@PathVariable UUID id, @RequestBody(required = false) ApiDtos.StartIngestRequest request) {
        String mode = request == null ? "incremental" : request.mode();
        boolean reprocessFailed = request != null && request.reprocessFailed();
        return dataSourceService.startIngest(id, mode, reprocessFailed);
    }

    @PostMapping("/{id}/cancel")
    public ApiDtos.JobItem cancel(@PathVariable UUID id) {
        return dataSourceService.cancelActiveJobs(id);
    }

    @PostMapping("/{id}/approve-degraded-processing")
    public ApiDtos.JobItem approveDegradedProcessing(@PathVariable UUID id) {
        return dataSourceService.approveDegradedProcessing(id);
    }

    @PostMapping("/{id}/index/reset")
    public WebAsyncTask<ApiDtos.IndexResetResult> resetIndex(@PathVariable UUID id) {
        return maintenanceTask(() -> dataSourceService.resetIndexedData(id));
    }

    @GetMapping("/{id}/files")
    public ApiDtos.PageResponse<ApiDtos.SourceFileItem> files(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return dataSourceService.listFiles(id, page, pageSize);
    }

    @GetMapping("/file-status/{sourceFileId}")
    public ApiDtos.SourceFileItem file(@PathVariable UUID sourceFileId) {
        return dataSourceService.getFile(sourceFileId);
    }

    private long maintenanceTimeoutMillis() {
        return Math.max(5, appProperties.maintenance().requestTimeoutSeconds()) * 1000L;
    }

    private <T> WebAsyncTask<T> maintenanceTask(Callable<T> action) {
        WebAsyncTask<T> task = new WebAsyncTask<>(
                maintenanceTimeoutMillis(),
                maintenanceTaskExecutor,
                action
        );
        task.onTimeout(() -> {
            throw new IllegalStateException("维护操作超时，已停止等待，请稍后重试");
        });
        return task;
    }
}
