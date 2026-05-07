<script setup lang="ts">
import type { QaAnswer, Citation, StructuredAnswer } from '@/types/query'
import AppPill from '@/components/common/AppPill.vue'
import AppCard from '@/components/common/AppCard.vue'

interface StructuredEntry {
  key: string
  label: string
  value: string | number | undefined
}

interface Props {
  answer: QaAnswer
  structuredEntries: StructuredEntry[]
}

defineProps<Props>()

const emit = defineEmits<{
  openOverview: [docId: string]
}>()

const getDocId = (citation: Citation): string => {
  return citation.docId || citation.doc_id || ''
}
</script>

<template>
  <div class="answer-result">
    <!-- Answer Box -->
    <div class="answer-box">
      <div class="answer-meta mb-md flex items-center gap-md">
        <AppPill variant="info">{{ answer.queryType || answer.query_type }}</AppPill>
        <span class="text-muted">引用 {{ answer.citations?.length || 0 }} 条</span>
      </div>
      <div class="answer-text">{{ answer.answer }}</div>
    </div>

    <!-- Doc Overview -->
    <div v-if="answer.docOverview || answer.doc_overview" class="overview-box mb-lg">
      <h4 class="mb-sm">文档画像</h4>
      <p class="text-muted mb-md">
        {{ (answer.docOverview || answer.doc_overview)?.summary || '-' }}
      </p>
      <div class="flex gap-lg">
        <span v-if="(answer.docOverview || answer.doc_overview)?.keyTopics?.length" class="text-muted">
          主题：{{ (answer.docOverview || answer.doc_overview)?.keyTopics?.join('、') }}
        </span>
        <span v-if="(answer.docOverview || answer.doc_overview)?.keywords?.length" class="text-muted">
          关键词：{{ (answer.docOverview || answer.doc_overview)?.keywords?.join('、') }}
        </span>
      </div>
    </div>

    <!-- Structured Result -->
    <div
      v-if="structuredEntries.length || (answer.structuredAnswer || answer.structured_answer)?.summaryPoints?.length"
      class="structured-box mb-lg"
    >
      <h4 class="mb-md">结构化结果</h4>
      <div v-if="structuredEntries.length" class="kv-grid">
        <div v-for="item in structuredEntries" :key="item.key" class="kv-item">
          <div class="kv-key">{{ item.label }}</div>
          <div class="kv-value">{{ item.value }}</div>
        </div>
      </div>
      <div
        v-if="(answer.structuredAnswer || answer.structured_answer)?.summaryPoints?.length"
        class="mt-lg"
      >
        <div class="kv-key mb-sm">摘要要点</div>
        <div class="points-list">
          <div
            v-for="(point, idx) in (answer.structuredAnswer || answer.structured_answer)?.summaryPoints"
            :key="idx"
            class="point-item"
          >
            {{ point }}
          </div>
        </div>
      </div>
    </div>

    <!-- Citations -->
    <div>
      <h4 class="mb-md">引用</h4>
      <div class="citations-list">
        <div v-for="(citation, idx) in answer.citations" :key="idx" class="citation-item">
          <strong>{{ citation.title || getDocId(citation) }}</strong>
          <div class="text-muted">原始文件：{{ citation.sourceFilename || citation.source_filename || citation.title || '-' }}</div>
          <div class="text-muted">原始路径：{{ citation.sourceFile || citation.source_file }}</div>
          <div
            v-if="(citation.relativePath || citation.relative_path) && (citation.relativePath || citation.relative_path) !== (citation.sourceFile || citation.source_file)"
            class="text-muted"
          >
            相对路径：{{ citation.relativePath || citation.relative_path }}
          </div>
          <div class="text-muted">docId={{ getDocId(citation) }}</div>
          <div v-if="citation.sourceSpan || citation.source_span" class="text-muted">
            位置：{{ citation.sourceSpan || citation.source_span }}
          </div>
          <div v-if="citation.pageNo || citation.page_no" class="text-muted">
            页码：{{ citation.pageNo || citation.page_no }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.answer-box {
  padding: var(--space-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
}

.answer-text {
  white-space: pre-wrap;
  line-height: 1.75;
}

.overview-box,
.structured-box {
  padding: var(--space-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-md);
}

.kv-item {
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
}

.kv-key {
  font-size: var(--text-xs);
  color: var(--text-muted);
  margin-bottom: var(--space-xs);
}

.kv-value {
  font-size: var(--text-base);
  line-height: 1.5;
}

.points-list,
.citations-list {
  display: grid;
  gap: var(--space-md);
}

.point-item,
.citation-item {
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
}

h4 {
  font-size: var(--text-lg);
  font-weight: 600;
}

@media (max-width: 640px) {
  .kv-grid {
    grid-template-columns: 1fr;
  }
}
</style>