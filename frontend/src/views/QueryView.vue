<script setup lang="ts">
import { useQueryStore } from '@/stores/useQueryStore'
import { useToastStore } from '@/stores/useToastStore'
import { submitQaQuery, submitSearch, getDocOverview } from '@/api/query'
import { HttpError } from '@/api/http'
import AppHeader from '@/components/common/AppHeader.vue'
import AppTabs from '@/components/common/AppTabs.vue'
import AppPanel from '@/components/common/AppPanel.vue'
import AppCard from '@/components/common/AppCard.vue'
import AppButton from '@/components/common/AppButton.vue'
import AppTextarea from '@/components/common/AppTextarea.vue'
import AppSelect from '@/components/common/AppSelect.vue'
import AppPill from '@/components/common/AppPill.vue'
import QueryForm from '@/components/query/QueryForm.vue'
import AnswerResult from '@/components/query/AnswerResult.vue'
import SearchResult from '@/components/query/SearchResult.vue'
import DocOverviewModal from '@/components/query/DocOverviewModal.vue'

const queryStore = useQueryStore()
const toastStore = useToastStore()

const topKOptions = [
  { value: '3', label: '3' },
  { value: '5', label: '5' },
  { value: '10', label: '10' },
  { value: '20', label: '20' },
]

const matchTypeOptions = [
  { value: 'all', label: '全部' },
  { value: 'title', label: '标题命中' },
  { value: 'filename', label: '文件名命中' },
  { value: 'summary', label: '摘要命中' },
  { value: 'caption', label: '图表标题命中' },
  { value: 'content', label: '正文命中' },
  { value: 'semantic', label: '语义命中' },
]

const tabs = [
  { key: 'qa', label: '智能问答' },
  { key: 'search', label: '全文检索' },
]

const submit = async () => {
  if (!queryStore.canSubmit) return
  queryStore.loading = true
  queryStore.error = ''
  queryStore.overviewModal = null

  try {
    if (queryStore.mode === 'qa') {
      queryStore.answer = await submitQaQuery({
        query: queryStore.queryText.trim(),
        excludeDevDocs: queryStore.excludeDevDocs,
        topK: Number(queryStore.topK || 5),
      })
      queryStore.searchResult = null
      toastStore.show('问答已完成')
    } else {
      queryStore.searchResult = await submitSearch({
        keyword: queryStore.queryText.trim(),
        excludeDevDocs: queryStore.excludeDevDocs,
        page: 1,
        pageSize: Number(queryStore.topK || 5),
      })
      queryStore.answer = null
      toastStore.show('检索已完成')
    }
  } catch (error) {
    if (error instanceof HttpError) {
      queryStore.error = error.message
    } else if (error instanceof Error) {
      queryStore.error = error.name === 'AbortError'
        ? '查询超时，请检查数据库初始化和索引状态'
        : error.message || '请求失败'
    }
  } finally {
    queryStore.loading = false
  }
}

const openOverview = async (docId: string) => {
  if (!docId) return
  queryStore.loadingOverviewId = docId
  try {
    const payload = await getDocOverview(docId)
    queryStore.overviewModal = payload || null
    toastStore.show('文档画像已加载')
  } catch (error) {
    queryStore.error = error instanceof Error ? error.message : '读取文档画像失败'
    toastStore.show('文档画像读取失败')
  } finally {
    queryStore.loadingOverviewId = ''
  }
}

const closeOverview = () => {
  queryStore.overviewModal = null
}
</script>

<template>
  <div class="page-container">
    <AppHeader>
      <template #left>
        <div class="header-brand">
          <span class="eyebrow">HmRAGCLI</span>
          <h1 class="header-title">知识查询</h1>
          <p class="header-subtitle text-muted">
            面向本地知识库的问答与检索入口。问答适合直接提问，检索适合快速翻找原文与证据。
          </p>
        </div>
      </template>
      <template #right>
        <AppButton variant="secondary" size="sm">
          <a href="/ui/ops">打开运维面板</a>
        </AppButton>
      </template>
    </AppHeader>

    <AppTabs
      :tabs="tabs"
      :active="queryStore.mode"
      @change="(key) => queryStore.setMode(key as 'qa' | 'search')"
    />

    <div class="query-layout">
      <QueryForm
        :mode="queryStore.mode"
        :query-text="queryStore.queryText"
        :top-k="String(queryStore.topK)"
        :exclude-dev-docs="queryStore.excludeDevDocs"
        :match-type-filter="queryStore.matchTypeFilter"
        :loading="queryStore.loading"
        :can-submit="queryStore.canSubmit"
        @update:query-text="queryStore.queryText = $event"
        @update:top-k="queryStore.topK = Number($event)"
        @update:exclude-dev-docs="(v: boolean) => queryStore.excludeDevDocs = v"
        @update:match-type-filter="queryStore.matchTypeFilter = $event"
        @submit="submit"
      />

      <AppCard class="result-panel">
        <template v-if="queryStore.loading">
          <div class="loading-state">
            <span class="spinner"></span>
            <span>{{ queryStore.mode === 'qa' ? '正在生成答案...' : '正在检索结果...' }}</span>
          </div>
        </template>

        <template v-else-if="queryStore.mode === 'qa'">
          <AnswerResult
            v-if="queryStore.answer"
            :answer="queryStore.answer"
            :structured-entries="queryStore.structuredEntries"
            @open-overview="openOverview"
          />
          <div v-else class="empty-state">
            还没有问答结果。输入问题后点击"开始问答"。
          </div>
        </template>

        <template v-else>
          <SearchResult
            v-if="queryStore.searchResult"
            :search-result="queryStore.searchResult"
            :filtered-items="queryStore.filteredSearchItems"
            :match-type-filter="queryStore.matchTypeFilter"
            :query-text="queryStore.queryText"
            :loading-overview-id="queryStore.loadingOverviewId"
            @open-overview="openOverview"
          />
          <div v-else class="empty-state">
            还没有检索结果。输入关键词后点击"开始检索"。
          </div>
        </template>
      </AppCard>
    </div>

    <div v-if="queryStore.error" class="error-banner">
      {{ queryStore.error }}
    </div>

    <DocOverviewModal
      v-if="queryStore.overviewModal"
      :overview="queryStore.overviewModal"
      @close="closeOverview"
    />
  </div>
</template>

<style scoped>
.query-layout {
  display: grid;
  grid-template-columns: minmax(320px, 1.2fr) minmax(420px, 1.8fr);
  gap: var(--space-xl);
}

.header-brand {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.eyebrow {
  font-size: var(--text-xs);
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.header-title {
  font-size: var(--text-4xl);
  font-weight: 600;
  letter-spacing: -0.02em;
}

.header-subtitle {
  font-size: var(--text-sm);
  max-width: 480px;
}

.result-panel {
  min-height: 400px;
}

.loading-state {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-xl);
  color: var(--text-muted);
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--border);
  border-right-color: var(--text);
  border-radius: 50%;
  animation: spin 600ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-banner {
  margin-top: var(--space-xl);
  padding: var(--space-lg);
  background: var(--error-bg);
  border: 1px solid var(--error);
  border-radius: var(--radius-md);
  color: var(--error-text);
}

@media (max-width: 960px) {
  .query-layout {
    grid-template-columns: 1fr;
  }
}
</style>