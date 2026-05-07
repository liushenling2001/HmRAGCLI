package com.hmrag.backend.web;

import com.hmrag.backend.service.AgentQueryService;
import com.hmrag.backend.web.dto.AgentQueryDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentQueryController {

    private final AgentQueryService agentQueryService;

    public AgentQueryController(AgentQueryService agentQueryService) {
        this.agentQueryService = agentQueryService;
    }

    @PostMapping("/search")
    public AgentQueryDtos.AgentSearchResponse search(@Valid @RequestBody AgentQueryDtos.AgentSearchRequest request) {
        return agentQueryService.search(request);
    }

    @PostMapping("/search/plan")
    public AgentQueryDtos.SearchPlanResponse plan(@Valid @RequestBody AgentQueryDtos.SearchPlanRequest request) {
        return agentQueryService.createPlan(request);
    }

    @PostMapping("/search/execute")
    public AgentQueryDtos.AgentSearchResponse execute(@RequestBody AgentQueryDtos.SearchExecuteRequest request) {
        return agentQueryService.executePlan(request);
    }

    @PostMapping("/answer")
    public AgentQueryDtos.AgentAnswerResponse answer(@Valid @RequestBody AgentQueryDtos.AgentAnswerRequest request) {
        return agentQueryService.answer(request);
    }

    @GetMapping("/tasks/{taskId}")
    public AgentQueryDtos.TaskStatusResponse taskStatus(@PathVariable UUID taskId) {
        return agentQueryService.taskStatus(taskId);
    }

    @GetMapping("/documents/{docId}/chunks")
    public AgentQueryDtos.DocumentChunksResponse documentChunks(
            @PathVariable UUID docId,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean includeContent
    ) {
        return agentQueryService.documentChunks(docId, section, pageNo, page, pageSize, includeContent);
    }
}

