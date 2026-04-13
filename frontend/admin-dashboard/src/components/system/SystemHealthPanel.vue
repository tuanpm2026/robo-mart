<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import Skeleton from 'primevue/skeleton'
import Button from 'primevue/button'
import ServiceHealthCard from './ServiceHealthCard.vue'
import { useSystemHealthStore } from '@/stores/useSystemHealthStore'

const store = useSystemHealthStore()

onMounted(() => {
  if (store.services.length === 0) {
    store.loadHealth()
  }
  tickerInterval = setInterval(() => { tick.value++ }, 1000)
})

onUnmounted(() => {
  if (tickerInterval) clearInterval(tickerInterval)
})

const tick = ref(0)
let tickerInterval: ReturnType<typeof setInterval> | null = null

const lastUpdatedLabel = computed(() => {
  void tick.value // subscribe to ticker
  const checkedAt = store.services[0]?.checkedAt
  if (!checkedAt) return ''
  const diff = Math.floor((Date.now() - new Date(checkedAt).getTime()) / 1000)
  if (diff < 0 || diff < 5) return 'just now'
  return `${diff} seconds ago`
})
</script>

<template>
  <div class="system-health-panel">
    <!-- Loading state -->
    <div v-if="store.isLoading" class="health-grid">
      <Skeleton v-for="n in 7" :key="n" height="80px" class="card-skeleton" />
    </div>

    <!-- Error state -->
    <div v-else-if="store.error" class="health-error">
      Failed to load health data.
      <Button label="Retry" size="small" @click="store.loadHealth()" />
    </div>

    <!-- Health grid -->
    <div v-else class="health-grid">
      <ServiceHealthCard
        v-for="service in store.services"
        :key="service.service"
        :service="service"
        :history="store.lagHistory[service.service] ?? []"
      />
    </div>

    <!-- Last updated -->
    <div v-if="store.services.length > 0" class="last-updated">
      Last updated: {{ lastUpdatedLabel }}
    </div>
  </div>
</template>

<style scoped>
.system-health-panel {
  padding: 4px 0;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.card-skeleton {
  border-radius: 6px;
}

.health-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px;
  color: var(--color-gray-600);
  font-size: 14px;
}

.last-updated {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-gray-400);
  text-align: right;
}
</style>
