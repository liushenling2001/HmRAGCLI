<script setup lang="ts">
import type { SystemHealth } from '@/types/ops'
import AppPanel from '@/components/common/AppPanel.vue'
import AppPill from '@/components/common/AppPill.vue'

interface Props {
  health: SystemHealth | null
  loading: boolean
}

defineProps<Props>()
</script>

<template>
  <AppPanel title="系统状态">
    <div v-if="!health" class="text-muted">
      点击"刷新系统状态"获取运行信息
    </div>
    <div v-else class="health-grid">
      <div v-for="item in health.checks || []" :key="item.name" class="health-card">
        <div class="health-row">
          <strong>{{ item.name }}</strong>
          <AppPill :variant="item.ok ? 'success' : 'error'">{{ item.ok ? 'OK' : 'FAIL' }}</AppPill>
        </div>
        <div class="text-muted">{{ item.error || JSON.stringify(item.detail || {}) }}</div>
      </div>
    </div>
  </AppPanel>
</template>

<style scoped>
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
}

.health-card {
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
}

.health-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}
</style>