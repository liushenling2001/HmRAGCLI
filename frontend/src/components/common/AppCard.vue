<script setup lang="ts">
interface Props {
  title?: string
  bordered?: boolean
}

withDefaults(defineProps<Props>(), {
  title: '',
  bordered: false,
})
</script>

<template>
  <div class="app-card" :class="{ 'card-bordered': bordered }">
    <div v-if="title || $slots.header" class="card-header">
      <slot name="header">
        <h3 v-if="title">{{ title }}</h3>
      </slot>
      <div v-if="$slots.actions" class="card-actions">
        <slot name="actions" />
      </div>
    </div>
    <div class="card-body">
      <slot />
    </div>
    <div v-if="$slots.footer" class="card-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<style scoped>
.app-card {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
}

.card-bordered {
  border: 1px solid var(--border);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-lg);
  border-bottom: 1px solid var(--border-light);
}

.card-header h3 {
  font-size: var(--text-lg);
  font-weight: 600;
  margin: 0;
}

.card-actions {
  display: flex;
  gap: var(--space-sm);
}

.card-body {
  padding: var(--space-lg);
}

.card-footer {
  padding: var(--space-lg);
  border-top: 1px solid var(--border-light);
}
</style>