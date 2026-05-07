<script setup lang="ts">
interface Props {
  current?: number
  total?: number
  pageSize?: number
}

const props = withDefaults(defineProps<Props>(), {
  current: 1,
  total: 1,
  pageSize: 20,
})

const emit = defineEmits<{
  change: [page: number]
}>()

const totalPages = computed(() => Math.max(1, props.total))

const pageWindow = computed(() => {
  const start = Math.max(1, props.current - 2)
  const end = Math.min(totalPages.value, start + 4)
  const adjustedStart = Math.max(1, end - 4)
  const pages: number[] = []
  for (let i = adjustedStart; i <= end; i++) {
    pages.push(i)
  }
  return pages
})

const goTo = (page: number) => {
  const safePage = Math.max(1, Math.min(totalPages.value, page))
  if (safePage !== props.current) {
    emit('change', safePage)
  }
}
</script>

<template>
  <div v-if="total > 1" class="app-pagination">
    <span class="pagination-info text-muted">
      第 {{ current }} / {{ totalPages }} 页
    </span>
    <div class="pagination-controls">
      <button
        class="pagination-btn"
        :disabled="current <= 1"
        @click="goTo(current - 1)"
      >
        上一页
      </button>
      <button
        v-for="page in pageWindow"
        :key="page"
        class="pagination-btn"
        :class="{ 'btn-active': page === current }"
        @click="goTo(page)"
      >
        {{ page }}
      </button>
      <button
        class="pagination-btn"
        :disabled="current >= totalPages"
        @click="goTo(current + 1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>

<script lang="ts">
import { computed } from 'vue'
</script>

<style scoped>
.app-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-top: var(--space-lg);
}

.pagination-info {
  font-size: var(--text-xs);
}

.pagination-controls {
  display: flex;
  gap: var(--space-xs);
}

.pagination-btn {
  height: 32px;
  min-width: 32px;
  padding: 0 var(--space-sm);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.pagination-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text);
}

.pagination-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-active {
  background: var(--text);
  color: var(--bg);
  border-color: var(--text);
}
</style>