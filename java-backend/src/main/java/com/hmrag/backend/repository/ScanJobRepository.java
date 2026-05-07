package com.hmrag.backend.repository;

import com.hmrag.backend.domain.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findTop20ByStatusInOrderByCreatedAtAsc(List<String> statuses);
}
