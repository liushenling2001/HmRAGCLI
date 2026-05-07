package com.hmrag.backend.repository;

import com.hmrag.backend.domain.DomainMemoryPack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DomainMemoryPackRepository extends JpaRepository<DomainMemoryPack, UUID> {
    List<DomainMemoryPack> findAllByOrderByCreatedAtDesc();
    List<DomainMemoryPack> findByDomainIdOrderByCreatedAtDesc(UUID domainId);
    List<DomainMemoryPack> findByTopicIdOrderByCreatedAtDesc(UUID topicId);
    List<DomainMemoryPack> findByRefineJobIdIn(List<UUID> refineJobIds);
    boolean existsByRefineJobId(UUID refineJobId);
}
