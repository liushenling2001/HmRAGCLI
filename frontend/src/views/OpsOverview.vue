<script setup lang="ts">
import { computed } from 'vue'
import { useOpsStore } from '@/stores/useOpsStore'
import AppPanel from '@/components/common/AppPanel.vue'
import AppButton from '@/components/common/AppButton.vue'
import AppPill from '@/components/common/AppPill.vue'
import AppProgress from '@/components/common/AppProgress.vue'
import AppPagination from '@/components/common/AppPagination.vue'
import AppTable from '@/components/common/AppTable.vue'
import SystemHealth from '@/components/ops/SystemHealth.vue'
import KpiGrid from '@/components/ops/KpiGrid.vue'
import DataSourceCard from '@/components/ops/DataSourceCard.vue'

const opsStore = useOpsStore()

interface Props {
  triggerAction: (sourceId: string, action: string) => Promise<void>
  handleCancelJobs: (source: any) => Promise<void>
  handleApproveDegraded: (source: any) => Promise<void>
  handleResetIndex: (source: any) => Promise<void>
  handleDeleteSource: (source: any) => Promise<void>
}

const props = defineProps<Props>()

const activeFiles = computed(() => opsStore.dashboard?.activeFiles || [])

const activeFilesColumns = [
  { key: 'fileName', label: '文件', width: '200px' },
  { key: 'lifecycleStatus', label: '状态', width: '120px' },
  { key: 'currentStage', label: '当前阶段', width: '120px' },
  { key: 'progress', label: '进度', width: '150px' },
  { key: 'errorSummary', label: '错误', width: '200px' },
]

function viewFiles(sourceId: string) {
  opsStore.selectedSourceId = sourceId
  opsStore.filesPage = 1
  opsStore.activeTab = 'files'
}

function switchSourceView(view: 'active' | 'completed') {
  opsStore.sourceView = view
  opsStore.sourcePage = 1
}

function changeSourcePage(page: number) {
  opsStore.sourcePage = page
}
</script>

<template>
  <div class="ops-overview">
    <!-- System Health -->
    <SystemHealth :health="opsStore.systemHealth" :loading="opsStore.loadingHealth" />

    <!-- KPI Grid -->
    <KpiGrid :overview="opsStore.overview" />

    <!-- Data Sources -->
    <AppPanel>
      <template #header>
        <h2>数据源</h2>
      </template>
      <template #actions>
        <AppButton variant="secondary" size="sm" :class="{ 'btn-active': opsStore.sourceView === 'active' }" @click="switchSourceView('active')">
          进行中
        </AppButton>
        <AppButton variant="secondary" size="sm" :class="{ 'btn-active': opsStore.sourceView === 'completed' }" @click="switchSourceView('completed')">
          已完成
        </AppButton>
      </template>

      <div class="source-grid">
        <DataSourceCard
          v-for="source in opsStore.visibleDataSources"
          :key="source.id"
          :source="source"
          :is-action-running="opsStore.isSourceActionRunning"
          :trigger-action="props.triggerAction"
          :handle-cancel-jobs="props.handleCancelJobs"
          :handle-approve-degraded="props.handleApproveDegraded"
          :handle-reset-index="props.handleResetIndex"
          :handle-delete-source="props.handleDeleteSource"
          :view-files="viewFiles"
        />
      </div>

      <AppPagination
        :current="opsStore.sourcePage"
        :total="opsStore.sourceTotalPages"
        @change="changeSourcePage"
      />
    </AppPanel>

    <!-- Active Files -->
    <AppPanel title="活跃文件">
      <AppTable
        :columns="activeFilesColumns"
        :data="activeFiles"
        :empty-text="activeFiles.length === 0 ? '暂无活跃文件' : ''"
      >
        <template #fileName="{ row }">
          <strong>{{ row.fileName }}</strong>
          <div class="text-muted">{{ row.dataSourceName || '-' }}</div>
        </template>
        <template #lifecycleStatus="{ row }">
          <AppPill :variant="opsStore.statusClass(row.lifecycleStatus)">
            {{ row.lifecycleStatus }}
          </AppPill>
        </template>
        <template #currentStage="{ row }">
          {{ opsStore.stageLabel(row.currentStage) }}
        </template>
        <template #progress="{ row }">
          <AppProgress :value="row.progressPercent" :max="100" show-label />
        </template>
        <template #errorSummary="{ row }">
          <span class="text-muted">{{ row.errorSummary || '-' }}</span>
        </template>
      </AppTable>
    </AppPanel>
  </div>
</template>

<style scoped>
.ops-overview {
  display: grid;
  gap: var(--space-xl);
}

.source-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: var(--space-lg);
}

.btn-active {
  background: var(--text);
  color: var(--bg);
  border-color: var(--text);
}
</style>