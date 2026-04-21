import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useReportsStore } from '@/stores/useReportsStore'
import * as reportsApi from '@/api/reportsApi'

vi.mock('@/api/reportsApi')
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: vi.fn() }),
}))

const mockSummary: reportsApi.ReportSummary = {
  topProducts: [{ productId: 1, productName: 'Widget A', totalQuantity: 50, totalRevenue: 499.5 }],
  revenueByProduct: [{ productName: 'Widget A', totalRevenue: 499.5 }],
  orderTrends: [{ date: '2026-04-10', status: 'CONFIRMED', count: 5 }],
}

describe('useReportsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads summary and sets state', async () => {
    vi.mocked(reportsApi.fetchReportSummary).mockResolvedValue(mockSummary)

    const store = useReportsStore()
    await store.loadSummary()

    expect(store.summary?.topProducts).toHaveLength(1)
    expect(store.summary?.topProducts[0]!.productName).toBe('Widget A')
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('handles API error and sets error message', async () => {
    vi.mocked(reportsApi.fetchReportSummary).mockRejectedValue(new Error('Network error'))

    const store = useReportsStore()
    await store.loadSummary()

    expect(store.error).toBe('Failed to load report summary')
    expect(store.isLoading).toBe(false)
    expect(store.summary).toBeNull()
  })

  it('rebuild triggers reload of summary', async () => {
    vi.mocked(reportsApi.triggerRebuild).mockResolvedValue('Rebuild initiated at 2026-04-10T00:00:00Z')
    vi.mocked(reportsApi.fetchReportSummary).mockResolvedValue(mockSummary)

    const store = useReportsStore()
    await store.rebuild()

    expect(reportsApi.triggerRebuild).toHaveBeenCalledOnce()
    expect(reportsApi.fetchReportSummary).toHaveBeenCalledOnce()
    expect(store.isRebuilding).toBe(false)
  })
})
