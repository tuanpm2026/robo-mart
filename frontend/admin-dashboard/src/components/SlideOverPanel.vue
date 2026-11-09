<script setup lang="ts">
import { watch } from 'vue'
import Drawer from 'primevue/drawer'

defineProps<{
  title?: string
}>()

const visible = defineModel<boolean>('visible', { default: false })
let trigger: HTMLElement | null = null
watch(
  visible,
  (open) => {
    if (open)
      trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  },
  { flush: 'sync' },
)
function restoreFocus() {
  if (trigger?.isConnected) trigger.focus()
}
</script>

<template>
  <Drawer
    block-scroll
    v-model:visible="visible"
    position="right"
    :style="{ width: 'min(640px, 100vw)', maxWidth: '100vw' }"
    :header="title"
    :aria-label="title"
    @after-hide="restoreFocus"
    :dismissable="true"
    :modal="true"
  >
    <template #header>
      <div class="slide-over-header">
        <span class="slide-over-title">{{ title }}</span>
      </div>
    </template>
    <div class="slide-over-body"><slot /></div>
  </Drawer>
</template>

<style scoped>
.slide-over-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.slide-over-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900);
}

.slide-over-body {
  min-width: 0;
  overflow-wrap: anywhere;
}
.slide-over-body :deep(.grid) {
  min-width: 0;
}
.slide-over-body :deep(table) {
  min-width: 480px;
}
@media (max-width: 480px) {
  .slide-over-body :deep(.grid-cols-2) {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
