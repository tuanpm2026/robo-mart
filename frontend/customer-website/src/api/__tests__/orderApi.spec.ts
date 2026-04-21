import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

describe('orderApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // --- getOrders ---

  describe('getOrders', () => {
    it('calls GET /api/v1/orders with correct params', async () => {
      const apiClient = (await import('../client')).default
      const mockResponse = {
        data: {
          data: [],
          pagination: { page: 0, size: 10, totalElements: 0, totalPages: 0 },
          traceId: 'trace-1',
        },
      }
      vi.mocked(apiClient.get).mockResolvedValueOnce(mockResponse)

      const { getOrders } = await import('../orderApi')
      const result = await getOrders({ page: 0, size: 10 })

      expect(apiClient.get).toHaveBeenCalledWith('/api/v1/orders', {
        params: { page: 0, size: 10 },
      })
      expect(result.data).toEqual([])
      expect(result.pagination.totalElements).toBe(0)
    })

    it('calls GET /api/v1/orders with no params when called without arguments', async () => {
      const apiClient = (await import('../client')).default
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          data: [],
          pagination: { page: 0, size: 10, totalElements: 0, totalPages: 0 },
          traceId: null,
        },
      })

      const { getOrders } = await import('../orderApi')
      await getOrders()

      expect(apiClient.get).toHaveBeenCalledWith('/api/v1/orders', { params: undefined })
    })
  })

  // --- getOrder ---

  describe('getOrder', () => {
    it('calls GET /api/v1/orders/123', async () => {
      const apiClient = (await import('../client')).default
      const orderDetail = {
        id: 123,
        createdAt: '2026-04-01T10:00:00Z',
        updatedAt: '2026-04-01T10:01:00Z',
        totalAmount: 99.99,
        status: 'CONFIRMED',
        shippingAddress: '123 Main St',
        cancellationReason: null,
        items: [],
        statusHistory: [],
      }
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: orderDetail, traceId: 'trace-1' },
      })

      const { getOrder } = await import('../orderApi')
      const result = await getOrder(123)

      expect(apiClient.get).toHaveBeenCalledWith('/api/v1/orders/123')
      expect(result.data.id).toBe(123)
      expect(result.data.status).toBe('CONFIRMED')
    })
  })

  // --- placeOrder ---

  describe('placeOrder', () => {
    it('posts to /api/v1/orders and returns created order summary', async () => {
      const apiClient = (await import('../client')).default
      const mockResponse = {
        data: {
          id: 1,
          createdAt: '2026-04-03T00:00:00Z',
          totalAmount: 20,
          status: 'CONFIRMED',
          itemCount: 1,
          cancellationReason: null,
        },
        traceId: 'trace-abc',
      }
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: mockResponse })

      const { placeOrder } = await import('../orderApi')
      const request = {
        items: [{ productId: '1', productName: 'Widget', quantity: 2, unitPrice: 10 }],
        shippingAddress: 'Jane Doe, 123 Main St, SF, CA 12345, US',
      }
      const result = await placeOrder(request)

      expect(apiClient.post).toHaveBeenCalledWith('/api/v1/orders', request)
      expect(result.data.id).toBe(1)
      expect(result.data.status).toBe('CONFIRMED')
    })
  })

  // --- cancelOrder ---

  describe('cancelOrder', () => {
    it('calls POST /api/v1/orders/123/cancel with reason body', async () => {
      const apiClient = (await import('../client')).default
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: null })

      const { cancelOrder } = await import('../orderApi')
      await cancelOrder(123, 'Changed my mind')

      expect(apiClient.post).toHaveBeenCalledWith('/api/v1/orders/123/cancel', {
        reason: 'Changed my mind',
      })
    })

    it('calls POST /api/v1/orders/456/cancel with undefined reason', async () => {
      const apiClient = (await import('../client')).default
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: null })

      const { cancelOrder } = await import('../orderApi')
      await cancelOrder(456, undefined)

      expect(apiClient.post).toHaveBeenCalledWith('/api/v1/orders/456/cancel', {
        reason: undefined,
      })
    })
  })
})
