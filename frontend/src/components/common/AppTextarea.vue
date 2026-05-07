<script setup lang="ts">
interface Props {
  modelValue?: string
  placeholder?: string
  disabled?: boolean
  rows?: number
  resize?: 'vertical' | 'horizontal' | 'none' | 'both'
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  placeholder: '',
  disabled: false,
  rows: 4,
  resize: 'vertical',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const handleInput = (event: Event) => {
  const target = event.target as HTMLTextAreaElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <textarea
    class="app-textarea"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :rows="rows"
    :style="{ resize }"
    @input="handleInput"
  />
</template>

<style scoped>
.app-textarea {
  width: 100%;
  min-height: 100px;
  padding: var(--space-md);
  font-size: var(--text-base);
  line-height: 1.6;
  color: var(--text);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.app-textarea:hover:not(:disabled) {
  border-color: var(--text-muted);
}

.app-textarea:focus {
  border-color: var(--border-focus);
  outline: none;
}

.app-textarea:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--bg-panel);
}

.app-textarea::placeholder {
  color: var(--text-soft);
}
</style>