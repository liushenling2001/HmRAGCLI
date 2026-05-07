<script setup lang="ts">
interface Column {
  key: string
  label: string
  width?: string
  align?: 'left' | 'center' | 'right'
}

interface Props {
  columns: Column[]
  data: Record<string, any>[]
  loading?: boolean
  emptyText?: string
  maxHeight?: string
}

withDefaults(defineProps<Props>(), {
  loading: false,
  emptyText: '暂无数据',
  maxHeight: '60vh',
})

const getCellValue = (row: Record<string, any>, key: string) => {
  return row[key]
}
</script>

<template>
  <div class="app-table" :style="{ maxHeight }">
    <table>
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="col.key"
            :style="{ width: col.width, textAlign: col.align || 'left' }"
          >
            {{ col.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length" class="table-loading">
            <span class="spinner"></span>
            <span>加载中...</span>
          </td>
        </tr>
        <tr v-else-if="!data.length">
          <td :colspan="columns.length" class="table-empty">
            {{ emptyText }}
          </td>
        </tr>
        <tr v-else v-for="(row, idx) in data" :key="idx">
          <td
            v-for="col in columns"
            :key="col.key"
            :style="{ textAlign: col.align || 'left' }"
          >
            <slot :name="col.key" :row="row" :value="getCellValue(row, col.key)">
              {{ getCellValue(row, col.key) }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.app-table {
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
}

table {
  width: 100%;
  border-collapse: collapse;
}

th, td {
  padding: var(--space-md);
  font-size: var(--text-base);
  vertical-align: top;
}

th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--bg-panel);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--border);
}

tbody tr {
  border-bottom: 1px solid var(--border-light);
  transition: background var(--transition-fast);
}

tbody tr:hover {
  background: var(--bg-hover);
}

tbody tr:last-child {
  border-bottom: none;
}

.table-loading,
.table-empty {
  text-align: center;
  color: var(--text-soft);
  padding: var(--space-xl);
}

.table-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--border);
  border-right-color: var(--text);
  border-radius: 50%;
  animation: spin 600ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>