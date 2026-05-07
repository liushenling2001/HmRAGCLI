<script setup lang="ts">
import { useOpsStore } from '@/stores/useOpsStore'
import { useToastStore } from '@/stores/useToastStore'
import { getFailures, cleanupTempFailures } from '@/api/ops'
import AppPanel from '@/components/common/AppPanel.vue'
import AppButton from '@/components/common/AppButton.vue'
import AppTable from '@/components/common/AppTable.vue'
import AppPagination from '@/components/common/AppPagination.vue'
import AppPill from '@/components/common/AppPill.vue'

const opsStore = useOpsStore()
const toastStore = useToastStore()

const failuresColumns = [
  { key: 'fileName', label: '文件', width: '200px' },
  { key: 'failedStage', label: '失败阶段', width: '120px' },
  { key: 'reason', label: '原因', width: '200px' },
  { key: 'detail', label: '详情', width: '200px' },
  { key: 'updatedAt', label: '时间', width: '160px' },
]

async function loadFailures() {
  try {
    const data = await getFailures(opsStore.failuresPage, opsStore.pageSize)
    opsStore.failures = data.items || []
    opsStore.failuresTotal = data.total || 0
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '加载失败记录失败')
  }
}

async function handleCleanup() {
  if (!confirm('确认清理失败列表中的 Office 临时文件（~$）吗？')) return
  try {
    const result = await cleanupTempFailures()
    toastStore.show(`已清理临时失败记录 ${result.cleanedFiles || 0} 条`)
    opsStore.failuresPage = 1
    await loadFailures()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '清理失败')
  }
}

function changeFailuresPage(page: number) {
  opsStore.failuresPage = page
  loadFailures()
}
</script>

<template>
  <AppPanel title="失败记录">
    <template #actions>
      <AppButton variant="secondary" size="sm" @click="handleCleanup">
        清理临时文件错误
      </AppButton>
    </template>

    <AppTable
      :columns="failuresColumns"
      :data="opsStore.failures"
      :loading="opsStore.loading"
      :empty-text="opsStore.failures.length === 0 ? '暂无失败文件' : ''"
    >
      <template #fileName="{ row }">
        <strong>{{ row.fileName }}</strong>
        <div class="text-muted">{{ row.dataSourceName || '-' }}</div>
      </template>
      <template #failedStage="{ row }">
        <AppPill variant="error">{{ row.failedStage || '-' }}</AppPill>
      </template>
      <template #reason="{ row }">
        {{ row.errorSummary || '-' }}
      </template>
      <template #detail="{ row }">
        <span class="text-muted">{{ row.errorDetail || '-' }}</span>
      </template>
      <template #updatedAt="{ row }">
        {{ opsStore.formatDate(row.updatedAt) }}
      </template>
    </AppTable>

    <AppPagination
      :current="opsStore.failuresPage"
      :total="opsStore.failuresTotalPages"
      @change="changeFailuresPage"
    />
  </AppPanel>
</template>