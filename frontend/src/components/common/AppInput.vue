<script setup lang="ts">
interface Props {
  modelValue?: string
  type?: 'text' | 'number' | 'password' | 'email'
  placeholder?: string
  disabled?: boolean
  error?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  type: 'text',
  placeholder: '',
  disabled: false,
  error: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

const handleInput = (event: Event) => {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <input
    class="app-input"
    :class="{ 'input-error': error }"
    :type="type"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="handleInput"
    @blur="emit('blur', $event)"
    @focus="emit('focus', $event)"
  />
</template>

<style scoped>
.app-input {
  width: 100%;
  height: 40px;
  padding: 0 var(--space-md);
  font-size: var(--text-base);
  color: var(--text);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.app-input:hover:not(:disabled) {
  border-color: var(--text-muted);
}

.app-input:focus {
  border-color: var(--border-focus);
  outline: none;
}

.app-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--bg-panel);
}

.input-error {
  border-color: var(--error);
}

.input-error:focus {
  border-color: var(--error);
}
</style>