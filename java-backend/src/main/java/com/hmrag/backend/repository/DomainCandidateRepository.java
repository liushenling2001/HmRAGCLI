package com.hmrag.backend.repository;

import com.hmrag.backend.domain.DomainCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DomainCandidateRepository extends JpaRepository<DomainCandidate, UUID> {
    List<DomainCandidate> findAllByOrderByCreatedAtDesc();
    List<DomainCandidate> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
    Optional<DomainCandidate> findTop1ByTriggerSourceOrderByCreatedAtDesc(String triggerSource);
    boolean existsByNameIgnoreCaseAndStatusIn(String name, Collection<String> statuses);
}
