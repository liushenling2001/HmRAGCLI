<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  value?: number
  max?: number
  variant?: 'default' | 'success' | 'warning' | 'error' | 'processing'
  showLabel?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  value: 0,
  max: 100,
  variant: 'default',
  showLabel: false,
})

const percentage = computed(() => {
  const v = Math.max(0, Math.min(props.max, props.value))
  return Math.round((v / props.max) * 100)
})
</script>

<template>
  <div class="app-progress">
    <div v-if="showLabel" class="progress-label">
      {{ value }}/{{ max }} ({{ percentage }}%)
    </div>
    <div class="progress-bar">
      <div
        class="progress-fill"
        :class="`fill-${variant}`"
        :style="{ width: `${percentage}%` }"
      />
    </div>
  </div>
</template>

<style scoped>
.app-progress {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.progress-label {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.progress-bar {
  width: 100%;
  height: 6px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  transition: width var(--transition-base);
}

.fill-default {
  background: var(--text-muted);
}

.fill-success {
  background: var(--success);
}

.fill-warning {
  background: var(--warning);
}

.fill-error {
  background: var(--error);
}

.fill-processing {
  background: var(--info);
}
</style>