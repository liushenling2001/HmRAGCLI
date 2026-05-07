package com.hmrag.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "source_files")
public class SourceFile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;

    @Column(name = "file_path", nullable = false, length = 2000)
    private String filePath;

    @Column(name = "relative_path", length = 1000)
    private String relativePath;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_ext", length = 20)
    private String fileExt;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mtime")
    private OffsetDateTime mtime;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "discover_status", nullable = false, length = 50)
    private String discoverStatus = "active";

    @Column(name = "ingest_status", nullable = false, length = 50)
    private String ingestStatus = "pending";

    @Column(name = "processing_stage", nullable = false, length = 50)
    private String processingStage = "discovered";

    @Column(name = "classification_status", nullable = false, length = 50)
    private String classificationStatus = "pending";

    @Column(name = "parse_status", nullable = false, length = 50)
    private String parseStatus = "pending";

    @Column(name = "extract_status", nullable = false, length = 50)
    private String extractStatus = "pending";

    @Column(name = "index_status", nullable = false, length = 50)
    private String indexStatus = "pending";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "duplicate_of_doc_id")
    private UUID duplicateOfDocId;

    @Column(name = "is_exact_duplicate", nullable = false)
    private boolean exactDuplicate;

    @Column(name = "is_possible_duplicate", nullable = false)
    private boolean possibleDuplicate;

    @Column(name = "last_scan_at")
    private OffsetDateTime lastScanAt;

    @Column(name = "last_ingest_at")
    private OffsetDateTime lastIngestAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(UUID dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileExt() { return fileExt; }
    public void setFileExt(String fileExt) { this.fileExt = fileExt; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public OffsetDateTime getMtime() { return mtime; }
    public void setMtime(OffsetDateTime mtime) { this.mtime = mtime; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getDiscoverStatus() { return discoverStatus; }
    public void setDiscoverStatus(String discoverStatus) { this.discoverStatus = discoverStatus; }
    public String getIngestStatus() { return ingestStatus; }
    public void setIngestStatus(String ingestStatus) { this.ingestStatus = ingestStatus; }
    public String getProcessingStage() { return processingStage; }
    public void setProcessingStage(String processingStage) { this.processingStage = processingStage; }
    public String getClassificationStatus() { return classificationStatus; }
    public void setClassificationStatus(String classificationStatus) { this.classificationStatus = classificationStatus; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public String getExtractStatus() { return extractStatus; }
    public void setExtractStatus(String extractStatus) { this.extractStatus = extractStatus; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public UUID getDuplicateOfDocId() { return duplicateOfDocId; }
    public void setDuplicateOfDocId(UUID duplicateOfDocId) { this.duplicateOfDocId = duplicateOfDocId; }
    public boolean isExactDuplicate() { return exactDuplicate; }
    public void setExactDuplicate(boolean exactDuplicate) { this.exactDuplicate = exactDuplicate; }
    public boolean isPossibleDuplicate() { return possibleDuplicate; }
    public void setPossibleDuplicate(boolean possibleDuplicate) { this.possibleDuplicate = possibleDuplicate; }
    public OffsetDateTime getLastScanAt() { return lastScanAt; }
    public void setLastScanAt(OffsetDateTime lastScanAt) { this.lastScanAt = lastScanAt; }
    public OffsetDateTime getLastIngestAt() { return lastIngestAt; }
    public void setLastIngestAt(OffsetDateTime lastIngestAt) { this.lastIngestAt = lastIngestAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getDocId() { return docId; }
    public void setDocId(UUID docId) { this.docId = docId; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
