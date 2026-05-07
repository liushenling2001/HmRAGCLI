<script setup lang="ts">
interface Props {
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info'
  size?: 'sm' | 'md'
}

withDefaults(defineProps<Props>(), {
  variant: 'default',
  size: 'md',
})
</script>

<template>
  <span class="app-pill" :class="[`pill-${variant}`, `pill-${size}`]">
    <slot />
  </span>
</template>

<style scoped>
.app-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  white-space: nowrap;
  border-radius: var(--radius-full);
}

.pill-sm {
  height: 24px;
  padding: 0 var(--space-sm);
  font-size: var(--text-xs);
}

.pill-md {
  height: 28px;
  padding: 0 var(--space-md);
  font-size: var(--text-xs);
}

/* Variants */
.pill-default {
  background: var(--bg-panel);
  color: var(--text-muted);
  border: 1px solid var(--border);
}

.pill-success {
  background: var(--success-bg);
  color: var(--success-text);
}

.pill-warning {
  background: var(--warning-bg);
  color: var(--warning-text);
}

.pill-error {
  background: var(--error-bg);
  color: var(--error-text);
}

.pill-info {
  background: var(--info-bg);
  color: var(--info-text);
}
</style>