<script setup lang="ts">
interface Tab {
  key: string
  label: string
}

interface Props {
  tabs: Tab[]
  active: string
}

defineProps<Props>()

const emit = defineEmits<{
  change: [key: string]
}>()
</script>

<template>
  <nav class="app-tabs">
    <button
      v-for="tab in tabs"
      :key="tab.key"
      class="tab-btn"
      :class="{ 'tab-active': tab.key === active }"
      @click="emit('change', tab.key)"
    >
      {{ tab.label }}
    </button>
  </nav>
</template>

<style scoped>
.app-tabs {
  display: flex;
  gap: var(--space-xs);
  padding: var(--space-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
  margin-bottom: var(--space-xl);
}

.tab-btn {
  height: 36px;
  padding: 0 var(--space-lg);
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-muted);
  background: transparent;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.tab-btn:hover {
  background: var(--bg-hover);
  color: var(--text);
}

.tab-active {
  background: var(--bg-card);
  color: var(--text);
  border: 1px solid var(--border-strong);
}
</style>