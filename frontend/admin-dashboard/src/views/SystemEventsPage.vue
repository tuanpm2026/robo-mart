<script setup lang="ts">
import { onMounted } from 'vue'
import DlqManager from '@/components/system/DlqManager.vue'
import { useDlqStore } from '@/stores/useDlqStore'

const store = useDlqStore()

onMounted(() => {
  store.loadEvents()
})
</script>

<template>
  <DlqManager />
</template>
