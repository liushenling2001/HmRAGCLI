<script setup lang="ts">
interface Props {
  sticky?: boolean
}

withDefaults(defineProps<Props>(), {
  sticky: false,
})
</script>

<template>
  <header class="app-header" :class="{ 'header-sticky': sticky }">
    <div class="header-left">
      <slot name="left" />
    </div>
    <div class="header-right">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-lg);
  padding: var(--space-lg) var(--space-xl);
  margin-bottom: var(--space-xl);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--bg-card);
}

.header-sticky {
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-left {
  flex: 1;
  min-width: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}
</style>