package com.hmrag.backend.web;

import com.hmrag.backend.service.TopicDefinitionService;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TopicDefinitionController {

    private final TopicDefinitionService topicDefinitionService;

    public TopicDefinitionController(TopicDefinitionService topicDefinitionService) {
        this.topicDefinitionService = topicDefinitionService;
    }

    @PostMapping("/domains/{domainId}/topics")
    public ApiDtos.TopicDefinitionItem create(
            @PathVariable UUID domainId,
            @Valid @RequestBody ApiDtos.CreateTopicDefinitionRequest request
    ) {
        return topicDefinitionService.create(domainId, request);
    }

    @GetMapping("/domains/{domainId}/topics")
    public List<ApiDtos.TopicDefinitionItem> listByDomain(@PathVariable UUID domainId) {
        return topicDefinitionService.listByDomain(domainId);
    }

    @GetMapping("/topics/{id}")
    public ApiDtos.TopicDefinitionItem get(@PathVariable UUID id) {
        return topicDefinitionService.get(id);
    }

    @PutMapping("/topics/{id}")
    public ApiDtos.TopicDefinitionItem update(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateTopicDefinitionRequest request
    ) {
        return topicDefinitionService.update(id, request);
    }

    @DeleteMapping("/topics/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        topicDefinitionService.delete(id);
    }
}
