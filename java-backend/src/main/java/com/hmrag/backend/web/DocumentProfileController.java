package com.hmrag.backend.web;

import com.hmrag.backend.service.DocumentProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/document-profiles")
public class DocumentProfileController {

    private final DocumentProfileService documentProfileService;

    public DocumentProfileController(DocumentProfileService documentProfileService) {
        this.documentProfileService = documentProfileService;
    }

    @GetMapping("/stats")
    public Map<String, Object> profileStats() {
        return documentProfileService.profileStats();
    }

    @PostMapping("/backfill")
    public Map<String, Object> backfillMissingProfiles(
            @RequestParam(defaultValue = "1000") int limit
    ) {
        return documentProfileService.backfillMissingProfiles(limit);
    }
}
