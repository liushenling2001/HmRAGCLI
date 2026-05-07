<script setup lang="ts">
import AppPanel from '@/components/common/AppPanel.vue'
import AppTextarea from '@/components/common/AppTextarea.vue'
import AppSelect from '@/components/common/AppSelect.vue'
import AppButton from '@/components/common/AppButton.vue'

interface Props {
  mode: 'qa' | 'search'
  queryText: string
  topK: string
  excludeDevDocs: boolean
  matchTypeFilter: string
  loading: boolean
  canSubmit: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  'update:queryText': [value: string]
  'update:topK': [value: string]
  'update:excludeDevDocs': [value: boolean]
  'update:matchTypeFilter': [value: string]
  submit: []
}>()

const topKOptions = [
  { value: '3', label: '3' },
  { value: '5', label: '5' },
  { value: '10', label: '10' },
  { value: '20', label: '20' },
]

const matchTypeOptions = [
  { value: 'all', label: '全部' },
  { value: 'title', label: '标题命中' },
  { value: 'filename', label: '文件名命中' },
  { value: 'summary', label: '摘要命中' },
  { value: 'caption', label: '图表标题命中' },
  { value: 'content', label: '正文命中' },
  { value: 'semantic', label: '语义命中' },
]
</script>

<template>
  <AppPanel :title="mode === 'qa' ? '提问' : '检索'">
    <p class="text-muted">
      {{ mode === 'qa'
        ? '输入一个自然语言问题，系统会返回答案、结构化摘要和引用。'
        : '输入关键词或主题，系统会返回文档级命中和片段证据。'
      }}
    </p>

    <AppTextarea
      :model-value="queryText"
      :placeholder="mode === 'qa' ? '例如：差旅报销的住宿标准是多少？' : '例如：研究生教育 数据 融合'"
      :rows="6"
      class="mt-lg"
      @update:model-value="emit('update:queryText', $event)"
    />

    <div class="filters mt-lg">
      <label class="checkbox-label">
        <input
          type="checkbox"
          :checked="excludeDevDocs"
          @change="emit('update:excludeDevDocs', !excludeDevDocs)"
        />
        <span>排除设计/开发文档</span>
      </label>

      <label class="select-label">
        <span class="text-muted">返回数量</span>
        <AppSelect
          :model-value="topK"
          :options="topKOptions"
          @update:model-value="emit('update:topK', $event)"
        />
      </label>

      <label v-if="mode === 'search'" class="select-label">
        <span class="text-muted">命中类型</span>
        <AppSelect
          :model-value="matchTypeFilter"
          :options="matchTypeOptions"
          @update:model-value="emit('update:matchTypeFilter', $event)"
        />
      </label>
    </div>

    <div class="mt-xl">
      <AppButton
        variant="primary"
        :disabled="!canSubmit"
        @click="emit('submit')"
      >
        {{ loading ? '处理中...' : (mode === 'qa' ? '开始问答' : '开始检索') }}
      </AppButton>
    </div>
  </AppPanel>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
  flex-wrap: wrap;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--text-base);
  cursor: pointer;
}

.checkbox-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: var(--text);
}

.select-label {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.select-label .app-select {
  width: 80px;
}
</style>