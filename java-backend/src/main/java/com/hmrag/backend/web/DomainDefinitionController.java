package com.hmrag.backend.web;

import com.hmrag.backend.service.DomainDefinitionService;
import com.hmrag.backend.service.DomainSetupAssistantService;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
@RequestMapping("/api/v1/domains")
public class DomainDefinitionController {

    private final DomainDefinitionService domainDefinitionService;
    private final DomainSetupAssistantService domainSetupAssistantService;

    public DomainDefinitionController(
            DomainDefinitionService domainDefinitionService,
            DomainSetupAssistantService domainSetupAssistantService
    ) {
        this.domainDefinitionService = domainDefinitionService;
        this.domainSetupAssistantService = domainSetupAssistantService;
    }

    @PostMapping
    public ApiDtos.DomainDefinitionItem create(@Valid @RequestBody ApiDtos.CreateDomainDefinitionRequest request) {
        return domainDefinitionService.create(request);
    }

    @GetMapping
    public List<ApiDtos.DomainDefinitionItem> list() {
        return domainDefinitionService.list();
    }

    @GetMapping("/{id}")
    public ApiDtos.DomainDefinitionItem get(@PathVariable UUID id) {
        return domainDefinitionService.get(id);
    }

    @PostMapping("/setup-assistant")
    public ApiDtos.DomainSetupAssistantResponse setupAssistant(
            @Valid @RequestBody ApiDtos.DomainSetupAssistantRequest request
    ) {
        return domainSetupAssistantService.assist(request);
    }

    @PostMapping(value = "/setup-assistant/stream", produces = "application/x-ndjson")
    public StreamingResponseBody setupAssistantStream(
            @Valid @RequestBody ApiDtos.DomainSetupAssistantRequest request
    ) {
        return outputStream -> domainSetupAssistantService.assistStream(request, outputStream);
    }

    @PutMapping("/{id}")
    public ApiDtos.DomainDefinitionItem update(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateDomainDefinitionRequest request
    ) {
        return domainDefinitionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        domainDefinitionService.delete(id);
    }
}
