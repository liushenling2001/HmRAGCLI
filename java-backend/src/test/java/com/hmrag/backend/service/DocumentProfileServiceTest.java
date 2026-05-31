package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProfileServiceTest {

    private final DocumentProfileService service = new DocumentProfileService(null, new ObjectMapper());

    @Test
    void projectPlanIsRoutedToProjectExtraction() {
        Map<String, Object> profile = service.buildProfile(
                Map.of(
                        "title", "学位论文智慧管理系统建设二期方案",
                        "sourceFilename", "建设方案.docx",
                        "docType", "rule",
                        "chunkCount", 18
                ),
                List.of(
                        chunk("建设目标", "本项目围绕学位论文智慧管理系统建设，包含模块、接口、性能指标、验收要求和应用单位。", "paragraph"),
                        chunk("升级内容", "二期升级覆盖论文评审、导师管理、统计分析模块。", "paragraph")
                ),
                List.of(Map.of("subject", "学位论文智慧管理系统", "indicator", "性能指标", "content", "平均响应时间小于5秒"))
        );

        assertThat(profile).containsEntry("docType", "project_plan");
        assertThat(profile).containsEntry("recommendedStrategy", "project_extraction");
        assertThat(profile).containsEntry("graphSuitability", "strong");
    }

    @Test
    void tableDocumentUsesAttributeFirstStrategy() {
        Map<String, Object> profile = service.buildProfile(
                Map.of("title", "经费明细表", "sourceFilename", "经费明细.xlsx", "docType", "excel", "chunkCount", 10),
                List.of(
                        chunk("明细", "序号 项目 金额 数量 单价 合计 10000 20 500", "table_row"),
                        chunk("明细", "备注 单位 指标 数值 30 40 50", "table_row")
                ),
                List.of()
        );

        assertThat(profile).containsEntry("docType", "table");
        assertThat(profile).containsEntry("structureType", "table_heavy");
        assertThat(profile).containsEntry("recommendedStrategy", "table_attribute_first");
    }

    @Test
    void speechIsWeakGraphAndSpeechSummary() {
        Map<String, Object> profile = service.buildProfile(
                Map.of("title", "在研究生教育工作会议上的讲话", "sourceFilename", "讲话稿.docx", "docType", "rule", "chunkCount", 6),
                List.of(chunk("会议讲话", "同志们，会议强调要贯彻落实相关精神，部署研究生教育高质量发展任务。", "paragraph")),
                List.of()
        );

        assertThat(profile).containsEntry("docType", "speech");
        assertThat(profile).containsEntry("recommendedStrategy", "speech_summary");
        assertThat(profile).containsEntry("graphSuitability", "weak");
    }

    private Map<String, Object> chunk(String title, String content, String chunkType) {
        return Map.of("title", title, "content", content, "chunkType", chunkType);
    }
}
