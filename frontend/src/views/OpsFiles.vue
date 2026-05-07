<script setup lang="ts">
import { watch } from 'vue'
import { useOpsStore } from '@/stores/useOpsStore'
import { useToastStore } from '@/stores/useToastStore'
import { getDataSourceFiles } from '@/api/ops'
import AppPanel from '@/components/common/AppPanel.vue'
import AppSelect from '@/components/common/AppSelect.vue'
import AppTable from '@/components/common/AppTable.vue'
import AppPagination from '@/components/common/AppPagination.vue'
import AppPill from '@/components/common/AppPill.vue'
import AppProgress from '@/components/common/AppProgress.vue'

const opsStore = useOpsStore()
const toastStore = useToastStore()

const filesColumns = [
  { key: 'fileName', label: '文件', width: '200px' },
  { key: 'lifecycleStatus', label: '生命周期', width: '120px' },
  { key: 'currentStage', label: '当前阶段', width: '140px' },
  { key: 'totalProgress', label: '总进度', width: '120px' },
  { key: 'stages', label: '四段进度', width: '200px' },
  { key: 'error', label: '错误', width: '200px' },
]

async function loadFiles() {
  if (!opsStore.selectedSourceId) {
    opsStore.files = []
    opsStore.filesTotal = 0
    return
  }
  try {
    const data = await getDataSourceFiles(opsStore.selectedSourceId, opsStore.filesPage, opsStore.pageSize)
    opsStore.files = data.items || []
    opsStore.filesTotal = data.total || 0
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '加载文件失败')
  }
}

function onSourceChange() {
  opsStore.filesPage = 1
  loadFiles()
}

function changeFilesPage(page: number) {
  opsStore.filesPage = page
  loadFiles()
}

// Watch for source changes
watch(() => opsStore.selectedSourceId, onSourceChange)
</script>

<template>
  <AppPanel>
    <template #header>
      <h2>文件列表</h2>
    </template>
    <template #actions>
      <AppSelect
        :model-value="opsStore.selectedSourceId"
        :options="opsStore.sourceOptions.map(s => ({ value: s.id, label: s.name }))"
        placeholder="选择数据源"
        @update:model-value="opsStore.selectedSourceId = $event"
      />
    </template>

    <AppTable
      :columns="filesColumns"
      :data="opsStore.files"
      :loading="opsStore.loading"
      :empty-text="opsStore.files.length === 0 ? '当前没有文件' : ''"
    >
      <template #fileName="{ row }">
        <strong>{{ row.fileName }}</strong>
        <div class="text-muted">{{ row.relativePath || row.filePath }}</div>
      </template>
      <template #lifecycleStatus="{ row }">
        <AppPill :variant="opsStore.statusClass(row.lifecycleStatus)">
          {{ row.lifecycleLabel || row.lifecycleStatus }}
        </AppPill>
      </template>
      <template #currentStage="{ row }">
        <div>{{ opsStore.stageLabel(row.currentStage) }}</div>
        <div class="text-muted">{{ row.errorSummary || '-' }}</div>
      </template>
      <template #totalProgress="{ row }">
        <AppProgress :value="row.progressPercent" :max="100" show-label />
      </template>
      <template #stages="{ row }">
        <div class="stages-stack">
          <div v-for="stageName in ['probe', 'parse', 'extract', 'vector']" :key="stageName" class="stage-chip">
            <span class="text-muted">{{ opsStore.stageLabel(stageName) }}</span>
            <span>{{ row.stageTasks?.find((s: any) => s.stage === stageName)?.completed || 0 }}/{{ row.stageTasks?.find((s: any) => s.stage === stageName)?.total || 0 }}</span>
          </div>
        </div>
      </template>
      <template #error="{ row }">
        <div>{{ row.errorSummary || '-' }}</div>
        <div class="text-muted">{{ row.errorDetail || '-' }}</div>
      </template>
    </AppTable>

    <AppPagination
      :current="opsStore.filesPage"
      :total="opsStore.filesTotalPages"
      @change="changeFilesPage"
    />
  </AppPanel>
</template>

<style scoped>
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