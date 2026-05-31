package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainCandidateServiceTest {

    @Test
    void candidateTermRejectsDocumentTitlesAndGenericNoise() throws Exception {
        DomainCandidateService service = new DomainCandidateService(
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
        Method method = DomainCandidateService.class.getDeclaredMethod("isCandidateDomainTerm", String.class);
        method.setAccessible(true);

        assertThat((Boolean) method.invoke(service, "研究生教育管理")).isTrue();
        assertThat((Boolean) method.invoke(service, "关于研究生教育管理平台建设方案的研究")).isFalse();
        assertThat((Boolean) method.invoke(service, "第3章 系统设计")).isFalse();
        assertThat((Boolean) method.invoke(service, "全文检索")).isFalse();
        assertThat((Boolean) method.invoke(service, "平台")).isFalse();
    }

    @Test
    void normalizeCandidateNameRemovesArticleStyleWrappers() throws Exception {
        DomainCandidateService service = new DomainCandidateService(
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
        Method method = DomainCandidateService.class.getDeclaredMethod("normalizeCandidateName", String.class);
        method.setAccessible(true);

        assertThat((String) method.invoke(service, "关于研究生教育管理的研究")).isEqualTo("研究生教育管理");
        assertThat((String) method.invoke(service, "《导师指导记录管理》")).isEqualTo("导师指导记录管理");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackDoesNotUseTitlesOrBlockedCandidatesAsDomains() throws Exception {
        DomainCandidateService service = new DomainCandidateService(
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
        Method method = DomainCandidateService.class.getDeclaredMethod(
                "fallbackCandidates",
                List.class,
                List.class,
                int.class,
                List.class
        );
        method.setAccessible(true);

        List<Map<String, Object>> recentSignals = List.of(Map.of(
                "signalType", "document",
                "title", "关于研究生教育管理平台建设方案的研究",
                "text", "研究生教育管理制度建设与培养质量保障。",
                "evidenceRef", "document:1"
        ));
        List<Map<String, Object>> emptyClusterSeeds = List.of();
        List<?> noSeedResult = (List<?>) method.invoke(service, recentSignals, emptyClusterSeeds, 5, List.of());
        assertThat(noSeedResult).isEmpty();

        List<Map<String, Object>> blockedSeed = List.of(Map.of(
                "category", "knowledge_unit_facet",
                "type", "subject",
                "term", "研究生教育管理",
                "count", 3L,
                "latestObserved", OffsetDateTime.now()
        ));
        List<?> blockedResult = (List<?>) method.invoke(
                service,
                recentSignals,
                blockedSeed,
                5,
                List.of("研究生教育管理")
        );
        assertThat(blockedResult).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackCanUseShortHighConnectedGraphEntityAsDomain() throws Exception {
        DomainCandidateService service = new DomainCandidateService(
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
        Method method = DomainCandidateService.class.getDeclaredMethod(
                "fallbackCandidates",
                List.class,
                List.class,
                int.class,
                List.class
        );
        method.setAccessible(true);

        List<Map<String, Object>> signals = List.of(Map.of(
                "signalType", "graph_entity",
                "title", "物联网",
                "text", "物联网 关系数 23 关联 边缘计算；传感器网络；智慧校园",
                "evidenceRef", "graph_entity:iot"
        ));
        List<Map<String, Object>> seeds = List.of(Map.of(
                "clusterType", "graph_top_connected_entity",
                "facet", "Technology",
                "term", "物联网",
                "signalCount", 23
        ));

        List<?> result = (List<?>) method.invoke(service, signals, seeds, 5, List.of());

        assertThat(result).hasSize(1);
        Object item = result.get(0);
        Method nameMethod = item.getClass().getDeclaredMethod("name");
        nameMethod.setAccessible(true);
        assertThat((String) nameMethod.invoke(item)).isEqualTo("物联网");
    }
}
