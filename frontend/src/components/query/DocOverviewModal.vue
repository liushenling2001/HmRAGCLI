<script setup lang="ts">
import type { DocOverview } from '@/types/query'
import AppModal from '@/components/common/AppModal.vue'

interface Props {
  overview: DocOverview
}

defineProps<Props>()

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <AppModal :open="true" title="文档画像详情" size="lg" @close="emit('close')">
    <div class="overview-content">
      <p class="text-muted mb-md">{{ overview.summary || '-' }}</p>

      <div class="meta-grid">
        <span v-if="overview.sections?.length" class="text-muted">
          章节：{{ overview.sections.join('、') }}
        </span>
        <span v-if="overview.keyTopics?.length" class="text-muted">
          主题：{{ overview.keyTopics.join('、') }}
        </span>
        <span v-if="overview.keywords?.length" class="text-muted">
          关键词：{{ overview.keywords.join('、') }}
        </span>
        <span v-if="overview.entities?.length" class="text-muted">
          实体：{{ overview.entities.join('、') }}
        </span>
        <span v-if="overview.timeRange" class="text-muted">
          时间范围：{{ overview.timeRange }}
        </span>
      </div>

      <div v-if="overview.conclusions?.length" class="mt-xl">
        <h4 class="mb-md">结论要点</h4>
        <div class="conclusions-list">
          <div v-for="(point, idx) in overview.conclusions" :key="idx" class="conclusion-item">
            {{ point }}
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <button class="app-button btn-secondary btn-md" @click="emit('close')">关闭</button>
    </template>
  </AppModal>
</template>

<style scoped>
.overview-content {
  padding: var(--space-md);
}

.meta-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-lg);
}

.conclusions-list {
  display: grid;
  gap: var(--space-md);
}

.conclusion-item {
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  font-size: var(--text-base);
  line-height: 1.6;
}

h4 {
  font-size: var(--text-lg);
  font-weight: 600;
}
</style>