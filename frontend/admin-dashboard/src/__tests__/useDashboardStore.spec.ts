import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useDashboardStore } from '@/stores/useDashboardStore'
import * as dashboardApi from '@/api/dashboardApi'

vi.mock('@/api/dashboardApi')

describe('useDashboardStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads metrics in parallel and sets state', async () => {
    vi.mocked(dashboardApi.fetchOrderMetrics).mockResolvedValue({
      ordersToday: 10,
      revenueToday: 500,
    })
    vi.mocked(dashboardApi.fetchInventoryMetrics).mockResolvedValue({ lowStockCount: 3 })

    const store = useDashboardStore()
    await store.loadMetrics()

    expect(store.ordersToday).toBe(10)
    expect(store.revenueToday).toBe(500)
    expect(store.lowStockCount).toBe(3)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('sets isLoading true during fetch and false after', async () => {
    let resolveOrder!: (v: { ordersToday: number; revenueToday: number }) => void
    vi.mocked(dashboardApi.fetchOrderMetrics).mockReturnValue(
      new Promise((r) => {
        resolveOrder = r
      }),
    )
    vi.mocked(dashboardApi.fetchInventoryMetrics).mockResolvedValue({ lowStockCount: 0 })

    const store = useDashboardStore()
    const promise = store.loadMetrics()
    expect(store.isLoading).toBe(true)

    resolveOrder({ ordersToday: 1, revenueToday: 50 })
    await promise
    expect(store.isLoading).toBe(false)
  })

  it('sets error and clears isLoading on API failure', async () => {
    vi.mocked(dashboardApi.fetchOrderMetrics).mockRejectedValue(new Error('Network error'))
    vi.mocked(dashboardApi.fetchInventoryMetrics).mockResolvedValue({ lowStockCount: 0 })

    const store = useDashboardStore()
    await store.loadMetrics()

    expect(store.error).toBe('Failed to load dashboard metrics')
    expect(store.isLoading).toBe(false)
  })
})
