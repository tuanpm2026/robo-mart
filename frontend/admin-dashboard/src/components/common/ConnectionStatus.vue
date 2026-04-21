<script setup lang="ts">
import { useWebSocketStore } from '@/stores/useWebSocketStore'

const store = useWebSocketStore()
</script>

<template>
  <div class="ws-status" aria-live="polite" :aria-label="`WebSocket: ${store.connectionStatus}`">
    <span class="ws-status-dot" :class="`ws-status-dot--${store.connectionStatus}`" />
    <span v-if="store.connectionStatus === 'reconnecting'" class="ws-status-label">
      Reconnecting...
    </span>
    <span
      v-else-if="store.connectionStatus === 'disconnected'"
      class="ws-status-label ws-status-label--error"
    >
      Disconnected
    </span>
  </div>
</template>

<style scoped>
.ws-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
}

.ws-status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ws-status-dot--connected {
  background: #22c55e;
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.2);
}

.ws-status-dot--reconnecting {
  background: #f59e0b;
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.2);
  animation: pulse 1.5s ease-in-out infinite;
}

.ws-status-dot--disconnected {
  background: #ef4444;
  box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2);
}

.ws-status-label {
  font-size: 12px;
  color: var(--color-gray-600);
}

.ws-status-label--error {
  color: #ef4444;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}
</style>
