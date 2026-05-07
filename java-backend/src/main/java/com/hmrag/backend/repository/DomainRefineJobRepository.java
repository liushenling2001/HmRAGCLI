package com.hmrag.backend.repository;

import com.hmrag.backend.domain.DomainRefineJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DomainRefineJobRepository extends JpaRepository<DomainRefineJob, UUID> {
    List<DomainRefineJob> findAllByOrderByCreatedAtDesc();
    List<DomainRefineJob> findByDomainIdOrderByCreatedAtDesc(UUID domainId);
    List<DomainRefineJob> findByTopicIdOrderByCreatedAtDesc(UUID topicId);
    List<DomainRefineJob> findTop10ByStatusOrderByCreatedAtAsc(String status);
    List<DomainRefineJob> findTop10ByStatusInOrderByCreatedAtAsc(List<String> statuses);
    List<DomainRefineJob> findTop100ByStatusOrderByCreatedAtDesc(String status);
    boolean existsByDomainIdAndStatusIn(UUID domainId, List<String> statuses);
    DomainRefineJob findTop1ByDomainIdAndTriggerSourceOrderByCreatedAtDesc(UUID domainId, String triggerSource);
}
