package com.hmrag.backend.web;

import com.hmrag.backend.service.DomainMemoryPackService;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/domain-memory-packs")
public class DomainMemoryPackController {

    private final DomainMemoryPackService domainMemoryPackService;

    public DomainMemoryPackController(DomainMemoryPackService domainMemoryPackService) {
        this.domainMemoryPackService = domainMemoryPackService;
    }

    @GetMapping
    public List<ApiDtos.DomainMemoryPackItem> list(
            @RequestParam(required = false) UUID domainId,
            @RequestParam(required = false) UUID topicId,
            @RequestParam(required = false) String triggerSource
    ) {
        return domainMemoryPackService.list(domainId, topicId, triggerSource);
    }

    @GetMapping("/agent-context")
    public List<ApiDtos.DomainMemoryPackItem> agentContext(
            @RequestParam(required = false) UUID domainId,
            @RequestParam(required = false) UUID topicId,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return domainMemoryPackService.listForAgent(domainId, topicId, limit);
    }

    @GetMapping("/{id}")
    public ApiDtos.DomainMemoryPackItem get(@PathVariable UUID id) {
        return domainMemoryPackService.get(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        domainMemoryPackService.delete(id);
    }

    @PostMapping("/{id}/review")
    public ApiDtos.DomainMemoryPackItem review(
            @PathVariable UUID id,
            @RequestBody @Valid ApiDtos.UpdateDomainMemoryPackReviewRequest request
    ) {
        return domainMemoryPackService.updateReview(id, request);
    }

    @GetMapping("/{id}/evidence")
    public List<ApiDtos.DomainEvidenceItem> evidence(@PathVariable UUID id) {
        return domainMemoryPackService.listEvidence(id);
    }

    @GetMapping("/{id}/context")
    public ApiDtos.DomainEvidenceContextResponse context(
            @PathVariable UUID id,
            @RequestParam String evidenceRef,
            @RequestParam(defaultValue = "1") int window
    ) {
        return domainMemoryPackService.getEvidenceContext(id, evidenceRef, window);
    }
}
