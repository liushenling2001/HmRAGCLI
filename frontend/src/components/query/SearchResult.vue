<script setup lang="ts">
import type { SearchResult, DocHit, EvidenceHit } from '@/types/query'
import AppPill from '@/components/common/AppPill.vue'
import AppButton from '@/components/common/AppButton.vue'
import { useHighlight } from '@/composables/useHighlight'

interface Props {
  searchResult: SearchResult
  filteredItems: EvidenceHit[]
  matchTypeFilter: string
  queryText: string
  loadingOverviewId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  openOverview: [docId: string]
}>()

const { escapeHtml, highlightHtml, matchTypeLabel } = useHighlight()

const getDocId = (doc: DocHit): string => doc.docId || doc.doc_id || ''
const getEvidenceDocId = (item: EvidenceHit): string => item.docId || item.doc_id || ''
const getDocTitle = (doc: DocHit): string => doc.docTitle || doc.doc_title || doc.sourceFilename || doc.source_filename || '未命名文档'
const getDocDownloadUrl = (doc: DocHit): string => {
  const docId = getDocId(doc)
  return docId ? `/api/v1/documents/${encodeURIComponent(docId)}/download` : ''
}
const canDownloadDoc = (doc: DocHit): boolean => !!getDocId(doc)
const triggerDownload = (doc: DocHit): void => {
  const url = getDocDownloadUrl(doc)
  if (!url) return
  const link = document.createElement('a')
  link.href = url
  link.rel = 'noopener'
  link.click()
}
const getEvidenceDownloadUrl = (item: EvidenceHit): string => {
  const docId = getEvidenceDocId(item)
  return docId ? `/api/v1/documents/${encodeURIComponent(docId)}/download` : ''
}
const triggerEvidenceDownload = (item: EvidenceHit): void => {
  const url = getEvidenceDownloadUrl(item)
  if (!url) return
  const link = document.createElement('a')
  link.href = url
  link.rel = 'noopener'
  link.click()
}
</script>

<template>
  <div class="search-result">
    <!-- Meta -->
    <div class="search-meta flex items-center gap-md mb-lg">
      <AppPill variant="info">文档命中 {{ searchResult.docHits?.length || searchResult.doc_hits?.length || 0 }} 条</AppPill>
      <AppPill variant="success">证据片段 {{ filteredItems.length }} 条</AppPill>
    </div>

    <!-- Doc Hits -->
    <div v-if="searchResult.docHits?.length || searchResult.doc_hits?.length">
      <h4 class="mb-md">文档级结果（第一跳）</h4>
      <div class="doc-list">
        <article
          v-for="doc in (searchResult.docHits || searchResult.doc_hits)"
          :key="getDocId(doc)"
          class="doc-item"
        >
          <div class="doc-header flex justify-between items-start gap-md mb-md">
            <div>
              <h3 class="doc-title" v-html="highlightHtml(getDocTitle(doc), queryText)"></h3>
              <div class="text-muted">
                score={{ Number(doc.score || 0).toFixed(3) }} / 命中片段 {{ doc.hitCount || doc.hit_count || 0 }}
              </div>
            </div>
            <AppButton
              variant="secondary"
              size="sm"
              :disabled="loadingOverviewId === getDocId(doc)"
              @click="emit('openOverview', getDocId(doc))"
            >
              {{ loadingOverviewId === getDocId(doc) ? '读取中...' : '查看文档画像' }}
            </AppButton>
            <AppButton
              variant="secondary"
              size="sm"
              :disabled="!canDownloadDoc(doc)"
              @click="triggerDownload(doc)"
            >
              下载原文
            </AppButton>
          </div>
          <div class="doc-content" v-html="highlightHtml(doc.overview?.summary || '-', queryText)"></div>
          <div class="doc-meta flex gap-md mt-md">
            <span class="text-muted" v-html="'原始文件：' + highlightHtml(doc.sourceFilename || doc.source_filename || '-', queryText)"></span>
            <span class="text-muted" v-html="'原始路径：' + highlightHtml(doc.sourceFile || doc.source_file || '-', queryText)"></span>
            <span
              v-if="(doc.relativePath || doc.relative_path) && (doc.relativePath || doc.relative_path) !== (doc.sourceFile || doc.source_file)"
              class="text-muted"
              v-html="'相对路径：' + highlightHtml(doc.relativePath || doc.relative_path || '-', props.queryText)"
            ></span>
            <span v-if="doc.overview?.keyTopics?.length" class="text-muted">
              主题：{{ doc.overview.keyTopics.join('、') }}
            </span>
          </div>
        </article>
      </div>
    </div>

    <!-- Evidence Hits -->
    <div class="mt-xl">
      <h4 class="mb-md">证据片段（第二跳）</h4>
      <div class="evidence-list">
        <article
          v-for="item in filteredItems"
          :key="(item.unitId || item.unit_id || item.chunkId || item.chunk_id || item.docId || item.doc_id)"
          class="evidence-item"
        >
          <div class="evidence-header flex justify-between items-start gap-md mb-md">
            <div>
              <h3 class="evidence-title" v-html="highlightHtml(item.title || item.docTitle || item.doc_title || '未命名结果', queryText)"></h3>
              <div class="text-muted">
                {{ item.docTitle || item.doc_title || '-' }} / {{ item.docType || item.doc_type || '-' }} / score={{ Number(item.score || 0).toFixed(3) }}
              </div>
            </div>
            <div class="flex gap-sm">
              <AppPill :variant="(item.kind || '') === 'knowledge_unit' ? 'success' : 'info'">
                {{ item.kind || '-' }}
              </AppPill>
              <AppPill variant="info">{{ matchTypeLabel(item.matchType || item.match_type) }}</AppPill>
              <AppPill :variant="(item.docDomain || item.doc_domain) === 'development' ? 'warning' : 'success'">
                {{ item.docDomain || item.doc_domain || '-' }}
              </AppPill>
              <AppButton
                variant="secondary"
                size="sm"
                :disabled="!getEvidenceDocId(item)"
                @click="triggerEvidenceDownload(item)"
              >
                下载原文
              </AppButton>
            </div>
          </div>
          <div class="evidence-content" v-html="highlightHtml(item.snippet || item.content || '-', queryText)"></div>
          <div class="evidence-meta flex gap-md mt-md">
            <span class="text-muted" v-html="'原始文件：' + highlightHtml(item.sourceFilename || item.source_filename || item.docTitle || item.doc_title || '-', queryText)"></span>
            <span class="text-muted" v-html="'原始路径：' + highlightHtml(item.sourceFile || item.source_file || '-', queryText)"></span>
            <span
              v-if="(item.relativePath || item.relative_path) && (item.relativePath || item.relative_path) !== (item.sourceFile || item.source_file)"
              class="text-muted"
              v-html="'相对路径：' + highlightHtml(item.relativePath || item.relative_path || '-', props.queryText)"
            ></span>
            <span v-if="item.subject" class="text-muted" v-html="'主题：' + highlightHtml(item.subject, props.queryText)"></span>
            <span v-if="item.indicator" class="text-muted" v-html="'指标：' + highlightHtml(item.indicator, props.queryText)"></span>
            <span v-if="item.sourceSpan || item.source_span" class="text-muted" v-html="'位置：' + highlightHtml(item.sourceSpan || item.source_span || '-', props.queryText)"></span>
            <span v-if="item.pageNo || item.page_no" class="text-muted">页码：{{ item.pageNo || item.page_no }}</span>
          </div>
        </article>
      </div>
    </div>

    <div v-if="(searchResult.evidenceHits || searchResult.evidence_hits || searchResult.items)?.length && !filteredItems.length" class="empty-state mt-lg">
      当前命中类型筛选下没有结果。
    </div>
  </div>
</template>

<style scoped>
h4 {
  font-size: var(--text-lg);
  font-weight: 600;
}

.doc-list,
.evidence-list {
  display: grid;
  gap: var(--space-lg);
}

.doc-item,
.evidence-item {
  padding: var(--space-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
}

.doc-title,
.evidence-title {
  font-size: var(--text-lg);
  font-weight: 600;
  margin: 0;
}

.doc-content,
.evidence-content {
  line-height: 1.65;
  white-space: pre-wrap;
}

.doc-content :deep(mark),
.evidence-content :deep(mark),
.doc-title :deep(mark),
.evidence-title :deep(mark),
.doc-meta :deep(mark),
.evidence-meta :deep(mark) {
  background: #fff1a8;
  color: #6a4b00;
  padding: 0 2px;
  border-radius: 2px;
}
</style>
