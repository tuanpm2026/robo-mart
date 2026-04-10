import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchOrderMetrics, fetchInventoryMetrics } from '@/api/dashboardApi'

export const useDashboardStore = defineStore('dashboard', () => {
  const ordersToday = ref(0)
  const revenueToday = ref(0)
  const lowStockCount = ref(0)
  const systemHealth = ref<'healthy' | 'degraded' | 'down'>('healthy')
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function loadMetrics() {
    isLoading.value = true
    error.value = null
    try {
      const [orderMetrics, inventoryMetrics] = await Promise.all([
        fetchOrderMetrics(),
        fetchInventoryMetrics(),
      ])
      ordersToday.value = orderMetrics.ordersToday
      revenueToday.value = orderMetrics.revenueToday
      lowStockCount.value = inventoryMetrics.lowStockCount
    } catch {
      error.value = 'Failed to load dashboard metrics'
    } finally {
      isLoading.value = false
    }
  }

  return {
    ordersToday,
    revenueToday,
    lowStockCount,
    systemHealth,
    isLoading,
    error,
    loadMetrics,
  }
})
