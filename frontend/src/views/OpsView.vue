<script setup lang="ts">
import { nextTick, onMounted, onUnmounted } from 'vue'
import { useOpsStore } from '@/stores/useOpsStore'
import { useToastStore } from '@/stores/useToastStore'
import {
  getDashboard,
  getSystemHealth,
  getDataSourceFiles,
  getJobs,
  getFailures,
  createDataSource,
  triggerSourceScan,
  triggerSourceIngest,
  cancelSourceJobs,
  approveDegradedProcessing,
  resetSourceIndex,
  deleteDataSource,
} from '@/api/ops'
import AppHeader from '@/components/common/AppHeader.vue'
import AppTabs from '@/components/common/AppTabs.vue'
import AppButton from '@/components/common/AppButton.vue'
import AppModal from '@/components/common/AppModal.vue'
import AppInput from '@/components/common/AppInput.vue'
import AppSelect from '@/components/common/AppSelect.vue'
import OpsOverview from '@/views/OpsOverview.vue'
import OpsFiles from '@/views/OpsFiles.vue'
import OpsJobs from '@/views/OpsJobs.vue'
import OpsKnowledge from '@/views/OpsKnowledge.vue'
import OpsFailures from '@/views/OpsFailures.vue'

const opsStore = useOpsStore()
const toastStore = useToastStore()

const tabs = [
  { key: 'overview', label: '总览' },
  { key: 'files', label: '文件' },
  { key: 'jobs', label: '任务' },
  { key: 'knowledge', label: '知识编译' },
  { key: 'failures', label: '失败' },
]

/* === Data Loading === */
async function loadDashboard() {
  opsStore.dashboard = await getDashboard()
  if (!opsStore.selectedSourceId && opsStore.dataSources.length) {
    opsStore.selectedSourceId = opsStore.dataSources[0].id
  }
  if (opsStore.sourcePage > opsStore.sourceTotalPages) {
    opsStore.sourcePage = opsStore.sourceTotalPages
  }
}

async function loadSystemHealth(silent = false) {
  opsStore.loadingHealth = true
  try {
    opsStore.systemHealth = await getSystemHealth()
    if (!silent) toastStore.show('系统状态已刷新')
  } finally {
    opsStore.loadingHealth = false
  }
}

async function loadFiles() {
  if (!opsStore.selectedSourceId) {
    opsStore.files = []
    opsStore.filesTotal = 0
    return
  }
  const params = { page: opsStore.filesPage, pageSize: opsStore.pageSize }
  const data = await getDataSourceFiles(opsStore.selectedSourceId, params.page, params.pageSize)
  opsStore.files = data.items || []
  opsStore.filesTotal = data.total || 0
}

async function loadJobsData() {
  const params = { page: opsStore.jobsPage, pageSize: opsStore.pageSize }
  const data = await getJobs(params.page, params.pageSize)
  opsStore.jobs = data.items || []
  opsStore.jobsTotal = data.total || 0
}

async function loadFailures() {
  const params = { page: opsStore.failuresPage, pageSize: opsStore.pageSize }
  const data = await getFailures(params.page, params.pageSize)
  opsStore.failures = data.items || []
  opsStore.failuresTotal = data.total || 0
}

async function refreshData() {
  await Promise.all([loadDashboard(), loadJobsData(), loadFailures()])
  await loadFiles()
}

/* === Source Actions === */
async function triggerAction(sourceId: string, action: string) {
  opsStore.setSourceActionRunning(sourceId, action, true)
  try {
    if (action === 'scan') {
      toastStore.show('扫描请求已提交')
      await triggerSourceScan(sourceId)
      toastStore.show('扫描已进入队列')
    } else if (action === 'retryFailed') {
      toastStore.show('重跑失败请求已提交')
      await triggerSourceIngest(sourceId, 'retry_failed')
      toastStore.show('失败文件重跑已进入队列')
    } else {
      toastStore.show('入库请求已提交')
      await triggerSourceIngest(sourceId, 'incremental')
      toastStore.show('入库已进入队列')
    }
    opsStore.activeTab = 'jobs'
    opsStore.jobsPage = 1
    await loadJobsData()
    refreshData().catch(() => {})
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '任务提交失败')
  } finally {
    opsStore.setSourceActionRunning(sourceId, action, false)
  }
}

async function handleCancelJobs(source: any) {
  if (!confirm(`确认停止数据源「${source.sourceName}」的运行任务吗？`)) return
  opsStore.setSourceActionRunning(source.id, 'cancel', true)
  toastStore.show(`已提交停止任务：${source.sourceName}`)
  try {
    await cancelSourceJobs(source.id)
    toastStore.show('停止请求已进入队列')
    opsStore.activeTab = 'jobs'
    opsStore.jobsPage = 1
    await loadJobsData()
    refreshData().catch(() => {})
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '停止任务失败')
  } finally {
    opsStore.setSourceActionRunning(source.id, 'cancel', false)
  }
}

async function handleApproveDegraded(source: any) {
  if (!confirm(`确认批准数据源「${source.sourceName}」在本次任务中允许降级继续执行吗？`)) return
  opsStore.setSourceActionRunning(source.id, 'approve', true)
  toastStore.show(`已提交人工批准：${source.sourceName}`)
  try {
    await approveDegradedProcessing(source.id)
    toastStore.show('已批准降级继续执行')
    opsStore.activeTab = 'jobs'
    opsStore.jobsPage = 1
    await loadJobsData()
    refreshData().catch(() => {})
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '批准降级失败')
  } finally {
    opsStore.setSourceActionRunning(source.id, 'approve', false)
  }
}

async function handleResetIndex(source: any) {
  if (!confirm(`确认清理数据源「${source.sourceName}」的已建索引吗？`)) return
  opsStore.setSourceActionRunning(source.id, 'reset', true)
  toastStore.show(`已提交清理索引：${source.sourceName}`)
  try {
    const result = await resetSourceIndex(source.id)
    const deletedDirCount = Array.isArray(result.deletedIndexDirs) ? result.deletedIndexDirs.length : 0
    toastStore.show(`清理完成：文档 ${result.deletedDocuments}，块 ${result.deletedChunks}，知识单元 ${result.deletedKnowledgeUnits}，目录 ${deletedDirCount}`)
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '清理索引失败')
  } finally {
    opsStore.setSourceActionRunning(source.id, 'reset', false)
    await refreshData()
  }
}

async function handleDeleteSource(source: any) {
  if (!confirm(`确认删除数据源「${source.sourceName}」吗？`)) return
  opsStore.setSourceActionRunning(source.id, 'delete', true)
  toastStore.show(`已提交删除数据源：${source.sourceName}`)
  try {
    await deleteDataSource(source.id)
    if (opsStore.selectedSourceId === source.id) {
      opsStore.selectedSourceId = ''
      opsStore.files = []
      opsStore.filesTotal = 0
    }
    toastStore.show(`已删除数据源：${source.sourceName}`)
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '删除数据源失败')
  } finally {
    opsStore.setSourceActionRunning(source.id, 'delete', false)
    await refreshData()
  }
}

/* === Source Modal === */
function openSourceModal() {
  opsStore.sourceModalOpen = true
}

function closeSourceModal() {
  opsStore.sourceModalOpen = false
  opsStore.sourceForm = {
    sourceName: '',
    rootPath: '',
    includePatterns: '*.pdf,*.doc,*.docx,*.xls,*.xlsx,*.txt,*.md',
    excludePatterns: '',
    recursive: true,
  }
}

async function createSource() {
  await createDataSource({
    sourceName: opsStore.sourceForm.sourceName.trim(),
    sourceType: 'local_dir',
    rootPath: opsStore.sourceForm.rootPath.trim(),
    includePatterns: opsStore.sourceForm.includePatterns.split(',').map((x) => x.trim()).filter(Boolean),
    excludePatterns: opsStore.sourceForm.excludePatterns.split(',').map((x) => x.trim()).filter(Boolean),
    recursive: opsStore.sourceForm.recursive,
    metadata: {},
  })
  closeSourceModal()
  toastStore.show('数据源创建成功')
  await refreshData()
}

/* === Tab Switching === */
async function switchTab(key: string) {
  opsStore.activeTab = key as any
  if (key === 'files') {
    opsStore.filesPage = 1
    await loadFiles()
  } else if (key === 'jobs') {
    opsStore.jobsPage = 1
    await loadJobsData()
  } else if (key === 'failures') {
    opsStore.failuresPage = 1
    await loadFailures()
  } else if (key === 'knowledge') {
    // Knowledge loading is handled by OpsKnowledge component
  }
}

/* === Polling === */
let pollTimer: ReturnType<typeof setTimeout> | null = null

function scheduleNextPoll() {
  const intervalMs = document.visibilityState === 'visible' ? 5000 : 20000
  pollTimer = setTimeout(async () => {
    try {
      if (document.visibilityState === 'visible') {
        await refreshData()
      } else {
        await loadSystemHealth(true)
      }
    } catch {
      // Ignore polling errors
    } finally {
      scheduleNextPoll()
    }
  }, intervalMs)
}

function startPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  scheduleNextPoll()
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    refreshData().catch(() => {})
  }
}

/* === Lifecycle === */
onMounted(async () => {
  ;(window as any).__OPS_BOOT_STAGE__ = 'ops_view_mounting'
  opsStore.loading = true
  try {
    await refreshData()
  } finally {
    opsStore.loading = false
  }
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startPolling()
  await nextTick()
  ;(window as any).__OPS_BOOT_STAGE__ = 'ops_view_ready'
  const loadingShell = document.getElementById('ops-static-shell')
  if (loadingShell) {
    loadingShell.style.display = 'none'
  }
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopPolling()
})
</script>

<template>
  <div class="page-container">
    <AppHeader :sticky="true">
      <template #left>
        <div class="header-brand">
          <span class="eyebrow">HmRAGCLI</span>
          <h1 class="header-title">Operations Dashboard</h1>
        </div>
      </template>
      <template #right>
        <AppButton variant="secondary" size="sm">
          <a href="/ui/query">查询页面</a>
        </AppButton>
        <AppButton variant="secondary" size="sm" @click="openSourceModal">新增数据源</AppButton>
        <AppButton variant="secondary" size="sm" :loading="opsStore.loadingHealth" @click="loadSystemHealth(false)">
          {{ opsStore.loadingHealth ? '刷新中...' : '刷新系统状态' }}
        </AppButton>
      </template>
    </AppHeader>

    <AppTabs :tabs="tabs" :active="opsStore.activeTab" @change="switchTab" />

    <OpsOverview v-if="opsStore.activeTab === 'overview'" :trigger-action="triggerAction" :handle-cancel-jobs="handleCancelJobs" :handle-approve-degraded="handleApproveDegraded" :handle-reset-index="handleResetIndex" :handle-delete-source="handleDeleteSource" />
    <OpsFiles v-else-if="opsStore.activeTab === 'files'" />
    <OpsJobs v-else-if="opsStore.activeTab === 'jobs'" />
    <OpsKnowledge v-else-if="opsStore.activeTab === 'knowledge'" />
    <OpsFailures v-else-if="opsStore.activeTab === 'failures'" />

    <!-- Source Modal -->
    <AppModal :open="opsStore.sourceModalOpen" title="新增数据源" size="md" @close="closeSourceModal">
      <form @submit.prevent="createSource">
        <div class="form-grid">
          <label class="form-label">
            <span>数据源名称</span>
            <AppInput v-model="opsStore.sourceForm.sourceName" required />
          </label>
          <label class="form-label">
            <span>根目录路径</span>
            <AppInput v-model="opsStore.sourceForm.rootPath" required />
          </label>
          <label class="form-label">
            <span>包含规则</span>
            <AppInput v-model="opsStore.sourceForm.includePatterns" />
          </label>
          <label class="form-label">
            <span>排除规则</span>
            <AppInput v-model="opsStore.sourceForm.excludePatterns" />
          </label>
          <label class="checkbox-label">
            <input type="checkbox" v-model="opsStore.sourceForm.recursive" />
            <span>递归扫描子文件夹</span>
          </label>
        </div>
      </form>
      <template #footer>
        <AppButton variant="secondary" @click="closeSourceModal">取消</AppButton>
        <AppButton variant="primary" @click="createSource">创建</AppButton>
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
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

.form-grid {
  display: grid;
  gap: var(--space-lg);
}

.form-label {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.form-label span:first-child {
  font-size: var(--text-sm);
  font-weight: 600;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.checkbox-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: var(--text);
}
</style>
