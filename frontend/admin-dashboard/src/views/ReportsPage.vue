<script setup lang="ts">
import { onMounted } from 'vue'
import ReportingPanel from '@/components/system/ReportingPanel.vue'
import { useReportsStore } from '@/stores/useReportsStore'

const store = useReportsStore()

onMounted(() => {
  store.loadSummary()
})
</script>

<template>
  <ReportingPanel />
</template>
