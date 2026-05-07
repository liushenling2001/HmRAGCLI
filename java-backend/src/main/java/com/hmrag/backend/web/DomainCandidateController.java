package com.hmrag.backend.web;

import com.hmrag.backend.service.DomainCandidateService;
import com.hmrag.backend.web.dto.ApiDtos;
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
@RequestMapping("/api/v1/domain-candidates")
public class DomainCandidateController {

    private final DomainCandidateService domainCandidateService;

    public DomainCandidateController(DomainCandidateService domainCandidateService) {
        this.domainCandidateService = domainCandidateService;
    }

    @GetMapping
    public List<ApiDtos.DomainCandidateItem> list(
            @RequestParam(required = false) String status
    ) {
        return domainCandidateService.list(status);
    }

    @PostMapping("/discover")
    public List<ApiDtos.DomainCandidateItem> discover(
            @RequestBody(required = false) ApiDtos.DiscoverDomainCandidatesRequest request
    ) {
        return domainCandidateService.discover(request);
    }

    @PostMapping("/{id}/accept")
    public ApiDtos.DomainDefinitionItem accept(
            @PathVariable UUID id,
            @RequestBody(required = false) ApiDtos.AcceptDomainCandidateRequest request
    ) {
        return domainCandidateService.accept(id, request);
    }

    @PostMapping("/{id}/reject")
    public ApiDtos.DomainCandidateItem reject(
            @PathVariable UUID id,
            @RequestBody(required = false) ApiDtos.RejectDomainCandidateRequest request
    ) {
        return domainCandidateService.reject(id, request);
    }
}
