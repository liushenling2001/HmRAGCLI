<script setup lang="ts">
import type { DashboardOverview } from '@/types/ops'
import AppCard from '@/components/common/AppCard.vue'

interface Props {
  overview: DashboardOverview
}

defineProps<Props>()

const kpis = [
  { key: 'totalDataSources', label: '数据源' },
  { key: 'totalFiles', label: '文件总数' },
  { key: 'acceptedFiles', label: '已接收' },
  { key: 'queuedFiles', label: '排队中' },
  { key: 'runningFiles', label: '运行中' },
  { key: 'readyFiles', label: '已完成' },
  { key: 'failedFiles', label: '失败' },
]
</script>

<template>
  <div class="kpi-grid">
    <AppCard v-for="kpi in kpis" :key="kpi.key" bordered>
      <div class="kpi-content">
        <div class="text-muted">{{ kpi.label }}</div>
        <div class="kpi-value">{{ overview[kpi.key as keyof DashboardOverview] || 0 }}</div>
      </div>
    </AppCard>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: var(--space-md);
}

.kpi-content {
  text-align: center;
}

.kpi-value {
  font-size: var(--text-3xl);
  font-weight: 700;
  color: var(--text);
  margin-top: var(--space-sm);
}

@media (max-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>