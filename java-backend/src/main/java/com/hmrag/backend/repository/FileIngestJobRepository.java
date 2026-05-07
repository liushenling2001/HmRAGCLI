package com.hmrag.backend.repository;

import com.hmrag.backend.domain.FileIngestJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileIngestJobRepository extends JpaRepository<FileIngestJob, UUID> {
    boolean existsByBatchJobIdAndSourceFileIdAndJobStage(UUID batchJobId, UUID sourceFileId, String jobStage);
    List<FileIngestJob> findByBatchJobId(UUID batchJobId);
    List<FileIngestJob> findBySourceFileId(UUID sourceFileId);
    List<FileIngestJob> findBySourceFileIdOrderByCreatedAtDesc(UUID sourceFileId);
    List<FileIngestJob> findBySourceFileIdIn(List<UUID> sourceFileIds);
    Optional<FileIngestJob> findByBatchJobIdAndSourceFileIdAndJobStage(UUID batchJobId, UUID sourceFileId, String jobStage);
    List<FileIngestJob> findTop200ByStatusInOrderByCreatedAtAsc(List<String> statuses);
    List<FileIngestJob> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
    long countByBatchJobIdAndStatus(UUID batchJobId, String status);
}
