<script setup lang="ts">
import { useToastStore } from '@/stores/useToastStore'

const toastStore = useToastStore()
</script>

<template>
  <Teleport to="body">
    <div
      class="toast-container"
      :class="{ 'toast-visible': toastStore.visible }"
    >
      {{ toastStore.message }}
    </div>
  </Teleport>
</template>

<style scoped>
.toast-container {
  position: fixed;
  right: 24px;
  bottom: 24px;
  max-width: 360px;
  padding: var(--space-md) var(--space-lg);
  background: var(--text);
  color: var(--bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  opacity: 0;
  transform: translateY(8px);
  transition: all var(--transition-base);
  z-index: 1100;
}

.toast-visible {
  opacity: 1;
  transform: translateY(0);
}
</style>