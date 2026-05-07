package com.hmrag.backend.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DowngradeApprovalService {

    private final Set<UUID> llmApprovedDataSources = ConcurrentHashMap.newKeySet();
    private final Set<UUID> embeddingApprovedDataSources = ConcurrentHashMap.newKeySet();

    public void approve(UUID dataSourceId, boolean llm, boolean embedding) {
        if (dataSourceId == null) {
            return;
        }
        if (llm) {
            llmApprovedDataSources.add(dataSourceId);
        }
        if (embedding) {
            embeddingApprovedDataSources.add(dataSourceId);
        }
    }

    public boolean isLlmApproved(UUID dataSourceId) {
        return dataSourceId != null && llmApprovedDataSources.contains(dataSourceId);
    }

    public boolean isEmbeddingApproved(UUID dataSourceId) {
        return dataSourceId != null && embeddingApprovedDataSources.contains(dataSourceId);
    }

    public void clear(UUID dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        llmApprovedDataSources.remove(dataSourceId);
        embeddingApprovedDataSources.remove(dataSourceId);
    }
}
