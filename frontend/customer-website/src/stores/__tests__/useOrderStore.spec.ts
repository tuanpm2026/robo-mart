import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useOrderStore } from '../useOrderStore'

vi.mock('@/api/orderApi', () => ({
  getOrders: vi.fn().mockResolvedValue({
    data: [
      {
        id: 1,
        createdAt: '2026-04-01T10:00:00Z',
        totalAmount: 99.99,
        status: 'CONFIRMED',
        itemCount: 2,
        cancellationReason: null,
      },
    ],
    pagination: { page: 0, size: 10, totalElements: 1, totalPages: 1 },
    traceId: 'trace-1',
  }),
  getOrder: vi.fn().mockResolvedValue({
    data: {
      id: 1,
      createdAt: '2026-04-01T10:00:00Z',
      updatedAt: '2026-04-01T10:01:00Z',
      totalAmount: 99.99,
      status: 'CONFIRMED',
      shippingAddress: '123 Main St',
      cancellationReason: null,
      items: [
        { productId: 10, productName: 'Widget', quantity: 2, unitPrice: 49.99, subtotal: 99.98 },
      ],
      statusHistory: [{ status: 'PENDING', changedAt: '2026-04-01T10:00:00Z' }],
    },
    traceId: 'trace-1',
  }),
  cancelOrder: vi.fn().mockResolvedValue(undefined),
}))

describe('useOrderStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should start with empty state', () => {
    const store = useOrderStore()
    expect(store.orders).toEqual([])
    expect(store.currentOrder).toBeNull()
    expect(store.pagination).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  // --- fetchOrders ---

  describe('fetchOrders', () => {
    it('happy path: stores orders and pagination', async () => {
      const store = useOrderStore()
      await store.fetchOrders()

      expect(store.orders).toHaveLength(1)
      expect(store.orders[0]!.id).toBe(1)
      expect(store.orders[0]!.status).toBe('CONFIRMED')
      expect(store.pagination).not.toBeNull()
      expect(store.pagination!.totalElements).toBe(1)
      expect(store.pagination!.totalPages).toBe(1)
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    })

    it('error path: stores error message', async () => {
      const { getOrders } = await import('@/api/orderApi')
      vi.mocked(getOrders).mockRejectedValueOnce(new Error('Network error'))

      const store = useOrderStore()
      await store.fetchOrders()

      expect(store.error).toBe('Network error')
      expect(store.orders).toEqual([])
      expect(store.isLoading).toBe(false)
    })

    it('error path: stores generic message for non-Error rejection', async () => {
      const { getOrders } = await import('@/api/orderApi')
      vi.mocked(getOrders).mockRejectedValueOnce('unexpected')

      const store = useOrderStore()
      await store.fetchOrders()

      expect(store.error).toBe('Failed to load orders')
    })

    it('sets isLoading to true during fetch', async () => {
      const store = useOrderStore()
      const promise = store.fetchOrders()
      expect(store.isLoading).toBe(true)
      await promise
      expect(store.isLoading).toBe(false)
    })
  })

  // --- fetchOrder ---

  describe('fetchOrder', () => {
    it('happy path: stores currentOrder', async () => {
      const store = useOrderStore()
      await store.fetchOrder(1)

      expect(store.currentOrder).not.toBeNull()
      expect(store.currentOrder!.id).toBe(1)
      expect(store.currentOrder!.status).toBe('CONFIRMED')
      expect(store.currentOrder!.items).toHaveLength(1)
      expect(store.currentOrder!.items[0]!.productName).toBe('Widget')
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    })

    it('error path: stores error message', async () => {
      const { getOrder } = await import('@/api/orderApi')
      vi.mocked(getOrder).mockRejectedValueOnce(new Error('Order not found'))

      const store = useOrderStore()
      await store.fetchOrder(999)

      expect(store.error).toBe('Order not found')
      expect(store.currentOrder).toBeNull()
    })
  })

  // --- cancelOrder ---

  describe('cancelOrder', () => {
    it('happy path: calls cancel API then re-fetches order', async () => {
      const { cancelOrder, getOrder } = await import('@/api/orderApi')

      const store = useOrderStore()
      await store.cancelOrder(1, 'Changed my mind')

      expect(cancelOrder).toHaveBeenCalledWith(1, 'Changed my mind')
      expect(getOrder).toHaveBeenCalledWith(1)
      expect(store.currentOrder).not.toBeNull()
      expect(store.isLoading).toBe(false)
    })

    it('error path: stores error and re-throws', async () => {
      const { cancelOrder } = await import('@/api/orderApi')
      vi.mocked(cancelOrder).mockRejectedValueOnce(new Error('Cannot cancel'))

      const store = useOrderStore()
      await expect(store.cancelOrder(1, 'test')).rejects.toThrow('Cannot cancel')
      expect(store.error).toBe('Cannot cancel')
    })
  })

  // --- $reset ---

  describe('$reset', () => {
    it('clears all state', async () => {
      const store = useOrderStore()
      await store.fetchOrders()
      await store.fetchOrder(1)

      store.$reset()

      expect(store.orders).toEqual([])
      expect(store.currentOrder).toBeNull()
      expect(store.pagination).toBeNull()
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    })
  })
})
