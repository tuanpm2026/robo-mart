import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { fetchSystemHealth } from '@/api/systemHealthApi'
import type { ServiceHealthData, SystemHealthResponse } from '@/api/systemHealthApi'

export function computeVisualStatus(s: ServiceHealthData): 'healthy' | 'degraded' | 'down' {
  if (s.actuatorStatus !== 'UP') return 'down'
  if (s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs > 1000) return 'down'
  if (s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs >= 200) return 'degraded'
  return 'healthy'
}

export const useSystemHealthStore = defineStore('systemHealth', () => {
  const services = ref<ServiceHealthData[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const lagHistory = ref<Record<string, number[]>>({})

  const overallHealth = computed((): 'healthy' | 'degraded' | 'down' => {
    if (services.value.length === 0) return 'healthy'
    const statuses = services.value.map(s => computeVisualStatus(s))
    if (statuses.some(s => s === 'down')) return 'down'
    if (statuses.some(s => s === 'degraded')) return 'degraded'
    return 'healthy'
  })

  async function loadHealth() {
    isLoading.value = true
    error.value = null
    try {
      const health = await fetchSystemHealth()
      services.value = health.services
    } catch {
      error.value = 'Failed to load system health data'
    } finally {
      isLoading.value = false
    }
  }

  function updateFromWebSocket(health: SystemHealthResponse) {
    services.value = health.services
    for (const svc of health.services) {
      if (svc.consumerGroup !== null) {
        updateLagHistory(svc.service, svc.kafkaConsumerLag)
      }
    }
  }

  function updateLagHistory(serviceName: string, lag: number | null) {
    if (!lagHistory.value[serviceName]) lagHistory.value[serviceName] = []
    lagHistory.value[serviceName].push(lag ?? 0)
    if (lagHistory.value[serviceName].length > 30) lagHistory.value[serviceName].shift()
  }

  return {
    services,
    isLoading,
    error,
    overallHealth,
    lagHistory,
    loadHealth,
    updateFromWebSocket,
    computeVisualStatus,
  }
})
