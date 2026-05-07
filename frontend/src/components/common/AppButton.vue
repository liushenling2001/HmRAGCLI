<script setup lang="ts">
interface Props {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'secondary',
  size: 'md',
  disabled: false,
  loading: false,
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

const handleClick = (event: MouseEvent) => {
  if (!props.disabled && !props.loading) {
    emit('click', event)
  }
}
</script>

<template>
  <button
    class="app-button"
    :class="[`btn-${variant}`, `btn-${size}`]"
    :disabled="disabled || loading"
    @click="handleClick"
  >
    <span v-if="loading" class="btn-spinner"></span>
    <slot />
  </button>
</template>

<style scoped>
.app-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  font-weight: 500;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  color: var(--text);
  transition: all var(--transition-fast);
}

.app-button:hover:not(:disabled) {
  background: var(--bg-hover);
}

.app-button:active:not(:disabled) {
  background: var(--bg-active);
}

.app-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Sizes */
.btn-sm {
  height: 32px;
  padding: 0 var(--space-md);
  font-size: var(--text-xs);
}

.btn-md {
  height: 40px;
  padding: 0 var(--space-lg);
  font-size: var(--text-base);
}

.btn-lg {
  height: 48px;
  padding: 0 var(--space-xl);
  font-size: var(--text-lg);
}

/* Variants */
.btn-primary {
  background: var(--text);
  border-color: var(--text);
  color: var(--bg);
}

.btn-primary:hover:not(:disabled) {
  background: var(--text-muted);
  border-color: var(--text-muted);
}

.btn-secondary {
  background: var(--bg-card);
  border-color: var(--border);
  color: var(--text);
}

.btn-danger {
  background: var(--error);
  border-color: var(--error);
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: var(--error-text);
  border-color: var(--error-text);
}

.btn-ghost {
  background: transparent;
  border-color: transparent;
  color: var(--text-muted);
}

.btn-ghost:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text);
}

/* Spinner */
.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 600ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>