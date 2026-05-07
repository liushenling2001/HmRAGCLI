package com.hmrag.backend.service;

import java.util.Map;

public class DomainKnowledgePauseException extends RuntimeException {

    private final Map<String, Object> metadata;

    public DomainKnowledgePauseException(String message, Map<String, Object> metadata) {
        super(message);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
