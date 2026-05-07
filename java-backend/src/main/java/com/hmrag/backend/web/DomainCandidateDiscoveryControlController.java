package com.hmrag.backend.web;

import com.hmrag.backend.service.DomainCandidateDiscoveryControlService;
import com.hmrag.backend.web.dto.ApiDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/domain-candidates/discovery-control")
public class DomainCandidateDiscoveryControlController {

    private final DomainCandidateDiscoveryControlService domainCandidateDiscoveryControlService;

    public DomainCandidateDiscoveryControlController(
            DomainCandidateDiscoveryControlService domainCandidateDiscoveryControlService
    ) {
        this.domainCandidateDiscoveryControlService = domainCandidateDiscoveryControlService;
    }

    @GetMapping
    public ApiDtos.DomainCandidateDiscoveryControlItem getStatus() {
        return domainCandidateDiscoveryControlService.getStatus();
    }

    @PostMapping("/start")
    public ApiDtos.DomainCandidateDiscoveryControlItem start() {
        return domainCandidateDiscoveryControlService.start();
    }

    @PostMapping("/stop")
    public ApiDtos.DomainCandidateDiscoveryControlItem stop() {
        return domainCandidateDiscoveryControlService.stop();
    }
}
