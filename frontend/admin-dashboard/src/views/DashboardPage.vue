<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import LiveOrderFeed from '@/components/dashboard/LiveOrderFeed.vue'
import { useWebSocket } from '@/composables/useWebSocket'
import { useWebSocketStore } from '@/stores/useWebSocketStore'

const { connect, disconnect } = useWebSocket()
const wsStore = useWebSocketStore()

onMounted(() => {
  connect()
})

onUnmounted(() => {
  disconnect()
})
</script>

<template>
  <div class="dashboard">
    <h1 class="admin-page-title">Dashboard</h1>
    <div class="dashboard-layout">
      <section class="dashboard-feed-panel">
        <LiveOrderFeed />
      </section>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  height: 100%;
}

.admin-page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin-bottom: 16px;
}

.dashboard-layout {
  display: grid;
  grid-template-columns: 1fr;
  gap: 24px;
  height: calc(100vh - 140px);
}

.dashboard-feed-panel {
  height: 100%;
  min-height: 400px;
}
</style>
