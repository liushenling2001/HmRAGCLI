package com.hmrag.backend.repository;

import com.hmrag.backend.domain.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {
    List<SourceFile> findByDataSourceIdOrderByFilePathAsc(UUID dataSourceId);
    Optional<SourceFile> findByDataSourceIdAndFilePath(UUID dataSourceId, String filePath);
    long countByDataSourceId(UUID dataSourceId);
    long countByDataSourceIdAndIngestStatus(UUID dataSourceId, String ingestStatus);
    List<SourceFile> findByDataSourceIdAndIngestStatusIn(UUID dataSourceId, List<String> statuses);
    List<SourceFile> findTop100ByIngestStatusOrderByUpdatedAtDesc(String ingestStatus);
}
