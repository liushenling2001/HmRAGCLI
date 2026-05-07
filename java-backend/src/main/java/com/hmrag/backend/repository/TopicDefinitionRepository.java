package com.hmrag.backend.repository;

import com.hmrag.backend.domain.TopicDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TopicDefinitionRepository extends JpaRepository<TopicDefinition, UUID> {
    List<TopicDefinition> findByDomainIdOrderByPriorityDescCreatedAtAsc(UUID domainId);
}
