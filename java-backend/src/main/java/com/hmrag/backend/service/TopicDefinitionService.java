package com.hmrag.backend.service;

import com.hmrag.backend.domain.DomainDefinition;
import com.hmrag.backend.domain.TopicDefinition;
import com.hmrag.backend.repository.DomainDefinitionRepository;
import com.hmrag.backend.repository.TopicDefinitionRepository;
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
public class TopicDefinitionService {

    private final TopicDefinitionRepository topicDefinitionRepository;
    private final DomainDefinitionRepository domainDefinitionRepository;

    public TopicDefinitionService(
            TopicDefinitionRepository topicDefinitionRepository,
            DomainDefinitionRepository domainDefinitionRepository
    ) {
        this.topicDefinitionRepository = topicDefinitionRepository;
        this.domainDefinitionRepository = domainDefinitionRepository;
    }

    @Transactional
    public ApiDtos.TopicDefinitionItem create(UUID domainId, ApiDtos.CreateTopicDefinitionRequest request) {
        requireDomain(domainId);
        TopicDefinition topic = new TopicDefinition();
        topic.setDomainId(domainId);
        apply(topic, request, domainId);
        return toItem(topicDefinitionRepository.save(topic));
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.TopicDefinitionItem> listByDomain(UUID domainId) {
        requireDomain(domainId);
        return topicDefinitionRepository.findByDomainIdOrderByPriorityDescCreatedAtAsc(domainId)
                .stream()
                .map(this::toItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.TopicDefinitionItem get(UUID id) {
        return toItem(requireTopic(id));
    }

    @Transactional
    public ApiDtos.TopicDefinitionItem update(UUID id, ApiDtos.UpdateTopicDefinitionRequest request) {
        TopicDefinition topic = requireTopic(id);
        apply(topic, request, topic.getDomainId());
        return toItem(topicDefinitionRepository.save(topic));
    }

    @Transactional
    public void delete(UUID id) {
        TopicDefinition topic = requireTopic(id);
        topicDefinitionRepository.delete(topic);
    }

    private void apply(TopicDefinition topic, ApiDtos.CreateTopicDefinitionRequest request, UUID domainId) {
        validateParentTopic(request.parentTopicId(), domainId, topic.getId());
        topic.setParentTopicId(request.parentTopicId());
        topic.setName(request.name().trim());
        topic.setDescription(trimToNull(request.description()));
        topic.setScopeRulesJson(copyMap(request.scopeRules()));
        topic.setSeedQueriesJson(copyList(request.seedQueries()));
        topic.setPriority(request.priority() == null ? 0 : request.priority());
        topic.setStatus(defaultStatus(request.status(), "active"));
        topic.setMetadataJson(copyMap(request.metadata()));
    }

    private void apply(TopicDefinition topic, ApiDtos.UpdateTopicDefinitionRequest request, UUID domainId) {
        validateParentTopic(request.parentTopicId(), domainId, topic.getId());
        topic.setParentTopicId(request.parentTopicId());
        topic.setName(request.name().trim());
        topic.setDescription(trimToNull(request.description()));
        topic.setScopeRulesJson(copyMap(request.scopeRules()));
        topic.setSeedQueriesJson(copyList(request.seedQueries()));
        topic.setPriority(request.priority() == null ? 0 : request.priority());
        topic.setStatus(defaultStatus(request.status(), "active"));
        topic.setMetadataJson(copyMap(request.metadata()));
    }

    private DomainDefinition requireDomain(UUID domainId) {
        return domainDefinitionRepository.findById(domainId)
                .orElseThrow(() -> new EntityNotFoundException("领域不存在: " + domainId));
    }

    private TopicDefinition requireTopic(UUID id) {
        return topicDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("专题不存在: " + id));
    }

    private void validateParentTopic(UUID parentTopicId, UUID domainId, UUID currentTopicId) {
        if (parentTopicId == null) {
            return;
        }
        TopicDefinition parent = requireTopic(parentTopicId);
        if (!parent.getDomainId().equals(domainId)) {
            throw new IllegalArgumentException("父专题不属于当前领域: " + parentTopicId);
        }
        if (currentTopicId != null && currentTopicId.equals(parentTopicId)) {
            throw new IllegalArgumentException("专题不能把自己设为父专题");
        }
    }

    private ApiDtos.TopicDefinitionItem toItem(TopicDefinition topic) {
        return new ApiDtos.TopicDefinitionItem(
                topic.getId(),
                topic.getDomainId(),
                topic.getParentTopicId(),
                topic.getName(),
                topic.getDescription(),
                copyMap(topic.getScopeRulesJson()),
                copyList(topic.getSeedQueriesJson()),
                topic.getPriority(),
                topic.getStatus(),
                copyMap(topic.getMetadataJson()),
                topic.getCreatedAt(),
                topic.getUpdatedAt()
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
