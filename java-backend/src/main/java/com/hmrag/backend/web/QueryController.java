package com.hmrag.backend.web;

import com.hmrag.backend.service.AgentQueryService;
import com.hmrag.backend.service.QueryService;
import com.hmrag.backend.web.dto.AgentQueryDtos;
import com.hmrag.backend.web.dto.QueryDtos;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

@RestController
public class QueryController {

    private final QueryService queryService;
    private final AgentQueryService agentQueryService;

    public QueryController(QueryService queryService, AgentQueryService agentQueryService) {
        this.queryService = queryService;
        this.agentQueryService = agentQueryService;
    }

    @GetMapping("/api/v1/search")
    public QueryDtos.SearchResponse search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "false") boolean excludeDevDocs,
            @RequestParam(defaultValue = "both") String hop,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return queryService.search(keyword, excludeDevDocs, page, pageSize, hop, null, null);
    }

    @GetMapping("/api/v1/documents/{id}/overview")
    public QueryDtos.DocumentOverviewResponse overview(@PathVariable UUID id) {
        return queryService.documentOverview(id);
    }

    @GetMapping("/api/v1/documents/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) throws Exception {
        QueryService.DocumentDownload download = queryService.resolveDocumentDownload(id);
        FileSystemResource resource = new FileSystemResource(download.path());
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("原始文件不可读: " + download.path());
        }
        String contentDisposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        MediaType mediaType = MediaTypeFactory.getMediaType(download.fileName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(Files.size(download.path())))
                .contentType(mediaType)
                .body(resource);
    }

    @GetMapping("/api/v1/documents/{id}/chunks")
    public AgentQueryDtos.DocumentChunksResponse chunks(
            @PathVariable UUID id,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean includeContent
    ) {
        return agentQueryService.documentChunks(id, section, pageNo, page, pageSize, includeContent);
    }

    @PostMapping("/api/v1/qa/query")
    public QueryDtos.QAQueryResponse qa(@Valid @RequestBody QueryDtos.QAQueryRequest request) {
        return queryService.answer(request.query(), request.excludeDevDocs(), request.topK());
    }
}
