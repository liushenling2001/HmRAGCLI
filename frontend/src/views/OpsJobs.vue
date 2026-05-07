<script setup lang="ts">
import { useOpsStore } from '@/stores/useOpsStore'
import { useToastStore } from '@/stores/useToastStore'
import { getJobs } from '@/api/ops'
import AppPanel from '@/components/common/AppPanel.vue'
import AppTable from '@/components/common/AppTable.vue'
import AppPagination from '@/components/common/AppPagination.vue'
import AppPill from '@/components/common/AppPill.vue'
import AppProgress from '@/components/common/AppProgress.vue'

const opsStore = useOpsStore()
const toastStore = useToastStore()

const jobsColumns = [
  { key: 'jobKind', label: '类型', width: '100px' },
  { key: 'dataSourceName', label: '数据源', width: '150px' },
  { key: 'status', label: '状态', width: '120px' },
  { key: 'fileProgress', label: '文件进度', width: '180px' },
  { key: 'stages', label: '阶段分布', width: '200px' },
  { key: 'startedAt', label: '开始时间', width: '160px' },
]

async function loadJobs() {
  try {
    const data = await getJobs(opsStore.jobsPage, opsStore.pageSize)
    opsStore.jobs = data.items || []
    opsStore.jobsTotal = data.total || 0
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '加载任务失败')
  }
}

function changeJobsPage(page: number) {
  opsStore.jobsPage = page
  loadJobs()
}
</script>

<template>
  <AppPanel title="任务队列">
    <AppTable
      :columns="jobsColumns"
      :data="opsStore.jobs"
      :loading="opsStore.loading"
      :empty-text="opsStore.jobs.length === 0 ? '暂无任务' : ''"
    >
      <template #jobKind="{ row }">
        <AppPill variant="info">{{ row.jobKind }}</AppPill>
      </template>
      <template #dataSourceName="{ row }">
        {{ row.dataSourceName || '-' }}
      </template>
      <template #status="{ row }">
        <AppPill :variant="opsStore.statusClass(row.status)">{{ row.status }}</AppPill>
      </template>
      <template #fileProgress="{ row }">
        <div class="progress-col">
          <span>{{ row.completedFiles }}/{{ row.totalFiles }} ({{ row.progressPercent }}%)</span>
          <AppProgress :value="row.progressPercent" :max="100" />
          <span class="text-muted">{{ row.currentStageSummary || '-' }}</span>
        </div>
      </template>
      <template #stages="{ row }">
        <div class="stages-stack">
          <div v-for="stage in row.stages" :key="stage.stage" class="stage-chip">
            <span class="text-muted">{{ opsStore.stageLabel(stage.stage) }}</span>
            <span>{{ stage.completedUnits }}/{{ stage.totalUnits }}</span>
          </div>
        </div>
      </template>
      <template #startedAt="{ row }">
        {{ opsStore.formatDate(row.startedAt) }}
      </template>
    </AppTable>

    <AppPagination
      :current="opsStore.jobsPage"
      :total="opsStore.jobsTotalPages"
      @change="changeJobsPage"
    />
  </AppPanel>
</template>

<style scoped>
.progress-col {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.stages-stack {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-xs);
}

.stage-chip {
  display: flex;
  justify-content: space-between;
  padding: var(--space-xs) var(--space-sm);
  background: var(--bg-panel);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
}
</style>