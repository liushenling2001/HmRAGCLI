package com.hmrag.backend.repository;

import com.hmrag.backend.domain.BatchIngestJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BatchIngestJobRepository extends JpaRepository<BatchIngestJob, UUID> {
}
