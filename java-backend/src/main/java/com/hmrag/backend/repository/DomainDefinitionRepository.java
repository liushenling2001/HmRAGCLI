package com.hmrag.backend.repository;

import com.hmrag.backend.domain.DomainDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DomainDefinitionRepository extends JpaRepository<DomainDefinition, UUID> {
    List<DomainDefinition> findAllByOrderByPriorityDescCreatedAtAsc();
    List<DomainDefinition> findByAutoRefreshEnabledTrueOrderByPriorityDescCreatedAtAsc();
}
