package com.hmrag.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphStoreClientTest {

    private final KnowledgeGraphStoreClient client = new KnowledgeGraphStoreClient(new ObjectMapper(), null);

    @Test
    void stateKeyDoesNotDependOnEntityDefinition() {
        String canonicalKey = "system:学位论文智慧管理系统";

        String first = client.stateKey(canonicalKey, Map.of(
                "definition", "一期建设的系统",
                "validFrom", "",
                "validTo", ""
        ));
        String second = client.stateKey(canonicalKey, Map.of(
                "definition", "二期升级后的系统",
                "validFrom", "",
                "validTo", ""
        ));

        assertThat(first).isEqualTo(canonicalKey + ":state:default");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void stateKeyUsesExplicitTemporalScopeOnly() {
        String canonicalKey = "system:学位论文智慧管理系统";

        String first = client.stateKey(canonicalKey, Map.of(
                "definition", "一期建设的系统",
                "validFrom", "2024-01-01",
                "validTo", ""
        ));
        String second = client.stateKey(canonicalKey, Map.of(
                "definition", "其他描述",
                "validFrom", "2025-01-01",
                "validTo", ""
        ));

        assertThat(first).isEqualTo(canonicalKey + ":state:2024-01-01:");
        assertThat(second).isEqualTo(canonicalKey + ":state:2025-01-01:");
    }

    @Test
    void descriptionKeyPreservesDescriptionAsSeparateEvidence() {
        String canonicalKey = "organization:清华大学";

        String descriptionKey = client.descriptionKey(canonicalKey, "doc-1", "m1", "一所高等学校");

        assertThat(descriptionKey)
                .startsWith(canonicalKey + ":description:doc-1:m1:")
                .doesNotContain(":state:");
    }
}
