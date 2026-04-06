<script setup lang="ts">
import Drawer from 'primevue/drawer'

defineProps<{
  title?: string
}>()

const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Drawer
    v-model:visible="visible"
    position="right"
    :style="{ width: '640px' }"
    :dismissable="true"
    :modal="true"
  >
    <template #header>
      <div class="slide-over-header">
        <span class="slide-over-title">{{ title }}</span>
      </div>
    </template>
    <slot />
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
</style>
