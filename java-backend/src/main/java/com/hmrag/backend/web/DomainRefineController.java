package com.hmrag.backend.web;

import com.hmrag.backend.service.DomainRefineJobService;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DomainRefineController {

    private final DomainRefineJobService domainRefineJobService;

    public DomainRefineController(DomainRefineJobService domainRefineJobService) {
        this.domainRefineJobService = domainRefineJobService;
    }

    @PostMapping("/domains/{domainId}/refine")
    public ApiDtos.DomainRefineJobItem startDomainRefine(
            @PathVariable UUID domainId,
            @RequestBody(required = false) @Valid ApiDtos.StartDomainRefineRequest request
    ) {
        return domainRefineJobService.startDomainRefine(domainId, request);
    }

    @PostMapping("/topics/{topicId}/refine")
    public ApiDtos.DomainRefineJobItem startTopicRefine(
            @PathVariable UUID topicId,
            @RequestBody(required = false) @Valid ApiDtos.StartTopicRefineRequest request
    ) {
        return domainRefineJobService.startTopicRefine(topicId, request);
    }

    @GetMapping("/refine-jobs")
    public List<ApiDtos.DomainRefineJobItem> list(
            @RequestParam(required = false) UUID domainId,
            @RequestParam(required = false) UUID topicId,
            @RequestParam(required = false) String triggerSource
    ) {
        return domainRefineJobService.list(domainId, topicId, triggerSource);
    }

    @GetMapping("/refine-jobs/{id}")
    public ApiDtos.DomainRefineJobItem get(@PathVariable UUID id) {
        return domainRefineJobService.get(id);
    }

    @PostMapping("/refine-jobs/{id}/cancel")
    public ApiDtos.DomainRefineJobItem cancel(@PathVariable UUID id) {
        return domainRefineJobService.cancel(id);
    }

    @PostMapping("/refine-jobs/{id}/resume")
    public ApiDtos.DomainRefineJobItem resume(@PathVariable UUID id) {
        return domainRefineJobService.resume(id);
    }
}
