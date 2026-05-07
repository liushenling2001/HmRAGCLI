package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.web.dto.ApiDtos;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DomainDefinitionService {

    private final DomainDefinitionRepository domainDefinitionRepository;

    public DomainDefinitionService(DomainDefinitionRepository domainDefinitionRepository) {
        this.domainDefinitionRepository = domainDefinitionRepository;
    }

    @Transactional
    public ApiDtos.DomainDefinitionItem create(ApiDtos.CreateDomainDefinitionRequest request) {
        DomainDefinition domain = new DomainDefinition();
        apply(domain, request);
        return toItem(domainDefinitionRepository.save(domain));
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.DomainDefinitionItem> list() {
        return domainDefinitionRepository.findAllByOrderByPriorityDescCreatedAtAsc()
                .stream()
                .map(this::toItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.DomainDefinitionItem get(UUID id) {
        return toItem(require(id));
    }

    @Transactional
    public ApiDtos.DomainDefinitionItem update(UUID id, ApiDtos.UpdateDomainDefinitionRequest request) {
        DomainDefinition domain = require(id);
        apply(domain, request);
        return toItem(domainDefinitionRepository.save(domain));
    }

    @Transactional
    public void delete(UUID id) {
        DomainDefinition domain = require(id);
        domainDefinitionRepository.delete(domain);
    }

    private DomainDefinition require(UUID id) {
        return domainDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("领域不存在: " + id));
    }

    private void apply(DomainDefinition domain, ApiDtos.CreateDomainDefinitionRequest request) {
        domain.setName(request.name().trim());
        domain.setDescription(trimToNull(request.description()));
        domain.setGoal(trimToNull(request.goal()));
        domain.setScopeRulesJson(copyMap(request.scopeRules()));
        domain.setSeedQueriesJson(copyList(request.seedQueries()));
        domain.setIncludeDataSourcesJson(copyList(request.includeDataSources()));
        domain.setExcludeDataSourcesJson(copyList(request.excludeDataSources()));
        domain.setPriority(request.priority() == null ? 0 : request.priority());
        domain.setAutoRefreshEnabled(Boolean.TRUE.equals(request.autoRefreshEnabled()));
        domain.setAutoRefreshCron(trimToNull(request.autoRefreshCron()));
        domain.setActiveModelProfile(trimToNull(request.activeModelProfile()));
        domain.setStatus(defaultStatus(request.status(), "draft"));
        domain.setCreatedBy(trimToNull(request.createdBy()));
        domain.setMetadataJson(copyMap(request.metadata()));
    }

    private void apply(DomainDefinition domain, ApiDtos.UpdateDomainDefinitionRequest request) {
        domain.setName(request.name().trim());
        domain.setDescription(trimToNull(request.description()));
        domain.setGoal(trimToNull(request.goal()));
        domain.setScopeRulesJson(copyMap(request.scopeRules()));
        domain.setSeedQueriesJson(copyList(request.seedQueries()));
        domain.setIncludeDataSourcesJson(copyList(request.includeDataSources()));
        domain.setExcludeDataSourcesJson(copyList(request.excludeDataSources()));
        domain.setPriority(request.priority() == null ? 0 : request.priority());
        domain.setAutoRefreshEnabled(Boolean.TRUE.equals(request.autoRefreshEnabled()));
        domain.setAutoRefreshCron(trimToNull(request.autoRefreshCron()));
        domain.setActiveModelProfile(trimToNull(request.activeModelProfile()));
        domain.setStatus(defaultStatus(request.status(), "draft"));
        domain.setCreatedBy(trimToNull(request.createdBy()));
        domain.setMetadataJson(copyMap(request.metadata()));
    }

    private ApiDtos.DomainDefinitionItem toItem(DomainDefinition domain) {
        return new ApiDtos.DomainDefinitionItem(
                domain.getId(),
                domain.getName(),
                domain.getDescription(),
                domain.getGoal(),
                copyMap(domain.getScopeRulesJson()),
                copyList(domain.getSeedQueriesJson()),
                copyList(domain.getIncludeDataSourcesJson()),
                copyList(domain.getExcludeDataSourcesJson()),
                domain.getPriority(),
                domain.isAutoRefreshEnabled(),
                domain.getAutoRefreshCron(),
                domain.getActiveModelProfile(),
                domain.getStatus(),
                domain.getCreatedBy(),
                copyMap(domain.getMetadataJson()),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultStatus(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }
}
