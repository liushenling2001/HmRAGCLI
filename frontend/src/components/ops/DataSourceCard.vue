<script setup lang="ts">
import type { DataSource, PipelineStage } from '@/types/ops'
import AppCard from '@/components/common/AppCard.vue'
import AppButton from '@/components/common/AppButton.vue'
import AppPill from '@/components/common/AppPill.vue'
import AppProgress from '@/components/common/AppProgress.vue'

interface Props {
  source: DataSource
  isActionRunning: (sourceId: string, action: string) => boolean
  triggerAction: (sourceId: string, action: string) => Promise<void>
  handleCancelJobs: (source: DataSource) => Promise<void>
  handleApproveDegraded: (source: DataSource) => Promise<void>
  handleResetIndex: (source: DataSource) => Promise<void>
  handleDeleteSource: (source: DataSource) => Promise<void>
  viewFiles: (sourceId: string) => void
}

const props = defineProps<Props>()

function sourceStage(source: DataSource, stageName: string): PipelineStage | null {
  return (source.stages || []).find((stage) => stage.stage === stageName) || null
}

function stagePercent(stage: PipelineStage | null): number {
  if (!stage) return 0
  const total = Number(stage.totalFiles || 0)
  const success = Number(stage.successFiles || 0)
  const skipped = Number(stage.skippedFiles || 0)
  if (!total) return 0
  return Math.min(100, Math.round(((success + skipped) * 100) / total))
}

function progressClassFromStatus(status: string): 'success' | 'processing' | 'error' | 'default' {
  const lowered = String(status || '').toLowerCase()
  if (['running', 'processing'].includes(lowered)) return 'processing'
  if (['success', 'fully_ready'].includes(lowered)) return 'success'
  if (['failed', 'build_failed'].includes(lowered)) return 'error'
  return 'default'
}

function sourceStageProgressClass(source: DataSource, stageName: string): 'success' | 'processing' | 'error' | 'default' {
  const stage = sourceStage(source, stageName)
  if (!stage) return 'default'
  if (Number(stage.runningFiles || 0) > 0) return 'processing'
  if (Number(stage.failedFiles || 0) > 0) return 'error'
  const pending = Number(stage.pendingFiles || 0)
  const success = Number(stage.successFiles || 0)
  const skipped = Number(stage.skippedFiles || 0)
  if (pending === 0 && (success + skipped) > 0) return 'success'
  return 'default'
}

function stageSummary(stage: PipelineStage | null): string {
  if (!stage) return '-'
  return `成功 ${stage.successFiles} / 运行 ${stage.runningFiles} / 失败 ${stage.failedFiles}`
}
</script>

<template>
  <AppCard bordered>
    <div class="source-header">
      <strong>{{ source.sourceName }}</strong>
      <AppPill variant="info">{{ source.sourceType }}</AppPill>
    </div>
    <div class="text-muted">{{ source.rootPath }}</div>

    <!-- Stats -->
    <div class="source-stats">
      <div class="stat-item">
        <strong>{{ source.totalFiles }}</strong>
        <div class="text-muted">总文件</div>
      </div>
      <div class="stat-item">
        <strong>{{ source.acceptedFiles }}</strong>
        <div class="text-muted">已接收</div>
      </div>
      <div class="stat-item">
        <strong>{{ source.queuedFiles }}</strong>
        <div class="text-muted">排队</div>
      </div>
      <div class="stat-item">
        <strong>{{ source.runningFiles }}</strong>
        <div class="text-muted">运行</div>
      </div>
      <div class="stat-item">
        <strong>{{ source.readyFiles }}</strong>
        <div class="text-muted">完成</div>
      </div>
      <div class="stat-item">
        <strong>{{ source.failedFiles }}</strong>
        <div class="text-muted">失败</div>
      </div>
    </div>

    <!-- Pipeline Progress -->
    <div class="pipeline-grid">
      <div v-for="stageName in ['probe', 'parse', 'extract', 'vector']" :key="stageName" class="pipeline-item">
        <div class="pipeline-head">
          <strong>{{ stageName === 'probe' ? '探测' : stageName === 'parse' ? '解析' : stageName === 'extract' ? '抽取' : '向量' }}</strong>
          <span class="text-muted">{{ stagePercent(sourceStage(source, stageName)) }}%</span>
        </div>
        <AppProgress
          :value="stagePercent(sourceStage(source, stageName))"
          :max="100"
          :variant="sourceStageProgressClass(source, stageName)"
        />
        <div class="text-muted">{{ stageSummary(sourceStage(source, stageName)) }}</div>
      </div>
    </div>

    <!-- Actions -->
    <div class="source-actions">
      <AppButton variant="secondary" size="sm" @click="viewFiles(source.id)">查看文件</AppButton>
      <AppButton variant="secondary" size="sm" :loading="isActionRunning(source.id, 'scan')" @click="triggerAction(source.id, 'scan')">
        {{ isActionRunning(source.id, 'scan') ? '扫描提交中...' : '扫描' }}
      </AppButton>
      <AppButton variant="secondary" size="sm" :loading="isActionRunning(source.id, 'retryFailed')" @click="triggerAction(source.id, 'retryFailed')">
        {{ isActionRunning(source.id, 'retryFailed') ? '提交中...' : '重跑失败' }}
      </AppButton>
      <AppButton variant="primary" size="sm" :loading="isActionRunning(source.id, 'ingest')" @click="triggerAction(source.id, 'ingest')">
        {{ isActionRunning(source.id, 'ingest') ? '入库提交中...' : '入库' }}
      </AppButton>
      <AppButton variant="secondary" size="sm" :loading="isActionRunning(source.id, 'cancel')" @click="handleCancelJobs(source)">
        {{ isActionRunning(source.id, 'cancel') ? '停止提交中...' : '停止任务' }}
      </AppButton>
      <AppButton variant="secondary" size="sm" :loading="isActionRunning(source.id, 'approve')" @click="handleApproveDegraded(source)">
        {{ isActionRunning(source.id, 'approve') ? '批准提交中...' : '批准降级' }}
      </AppButton>
      <AppButton variant="danger" size="sm" :loading="isActionRunning(source.id, 'reset')" @click="handleResetIndex(source)">
        {{ isActionRunning(source.id, 'reset') ? '清理中...' : '清理索引' }}
      </AppButton>
      <AppButton variant="danger" size="sm" :loading="isActionRunning(source.id, 'delete')" @click="handleDeleteSource(source)">
        {{ isActionRunning(source.id, 'delete') ? '删除中...' : '删除数据源' }}
      </AppButton>
    </div>
  </AppCard>
</template>

<style scoped>
.source-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.source-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

.stat-item {
  padding: var(--space-sm);
  background: var(--bg-panel);
  border-radius: var(--radius-sm);
  text-align: center;
}

.stat-item strong {
  font-size: var(--text-xl);
}

.pipeline-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

.pipeline-item {
  padding: var(--space-sm);
  background: var(--bg-panel);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
}

.pipeline-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
}

.source-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-top: var(--space-md);
}
</style>