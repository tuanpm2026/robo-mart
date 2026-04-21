import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useCartStore } from '../useCartStore'

vi.mock('@/api/cartApi', () => ({
  getCart: vi.fn().mockResolvedValue({
    data: {
      cartId: 'cart-123',
      items: [
        { productId: 1, productName: 'Product 1', price: 10.0, quantity: 2, subtotal: 20.0 },
        { productId: 2, productName: 'Product 2', price: 25.5, quantity: 1, subtotal: 25.5 },
      ],
      totalItems: 3,
      totalPrice: 45.5,
    },
    traceId: 'trace-1',
  }),
  addToCart: vi.fn().mockResolvedValue({
    data: {
      cartId: 'cart-123',
      items: [
        { productId: 1, productName: 'Product 1', price: 10.0, quantity: 2, subtotal: 20.0 },
        { productId: 2, productName: 'Product 2', price: 25.5, quantity: 1, subtotal: 25.5 },
      ],
      totalItems: 3,
      totalPrice: 45.5,
    },
    traceId: 'trace-1',
  }),
  updateQuantity: vi.fn().mockResolvedValue({
    data: {
      cartId: 'cart-123',
      items: [
        { productId: 1, productName: 'Product 1', price: 10.0, quantity: 2, subtotal: 20.0 },
        { productId: 2, productName: 'Product 2', price: 25.5, quantity: 1, subtotal: 25.5 },
      ],
      totalItems: 3,
      totalPrice: 45.5,
    },
    traceId: 'trace-1',
  }),
  removeItem: vi.fn().mockResolvedValue(undefined),
  mergeCart: vi.fn().mockResolvedValue({
    data: {
      cartId: 'user-uuid-123',
      items: [
        { productId: 1, productName: 'Product 1', price: 10.0, quantity: 3, subtotal: 30.0 },
        { productId: 2, productName: 'Product 2', price: 25.5, quantity: 1, subtotal: 25.5 },
        { productId: 3, productName: 'Product 3', price: 15.0, quantity: 2, subtotal: 30.0 },
      ],
      totalItems: 6,
      totalPrice: 85.5,
    },
    traceId: 'trace-merge',
  }),
}))

vi.mock('@/api/client', () => ({
  clearAnonymousIdCache: vi.fn(),
}))

describe('useCartStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should start with empty state', () => {
    const store = useCartStore()
    expect(store.items).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
    expect(store.totalItems).toBe(0)
    expect(store.totalPrice).toBe(0)
  })

  it('should fetch cart', async () => {
    const store = useCartStore()
    await store.fetchCart()
    expect(store.items).toHaveLength(2)
    expect(store.items[0]!.productName).toBe('Product 1')
    expect(store.isLoading).toBe(false)
  })

  it('should compute totalItems as sum of quantities', async () => {
    const store = useCartStore()
    await store.fetchCart()
    expect(store.totalItems).toBe(3)
  })

  it('should compute totalPrice as sum of subtotals', async () => {
    const store = useCartStore()
    await store.fetchCart()
    expect(store.totalPrice).toBe(45.5)
  })

  it('should set isLoading during fetch', async () => {
    const store = useCartStore()
    const promise = store.fetchCart()
    expect(store.isLoading).toBe(true)
    await promise
    expect(store.isLoading).toBe(false)
  })

  it('should handle fetch error', async () => {
    const { getCart } = await import('@/api/cartApi')
    vi.mocked(getCart).mockRejectedValueOnce(new Error('Network error'))

    const store = useCartStore()
    await store.fetchCart()
    expect(store.error).toBe('Network error')
    expect(store.items).toEqual([])
  })

  it('should add item optimistically', async () => {
    const store = useCartStore()
    await store.addItem({
      productId: 3,
      productName: 'New Product',
      price: 15.0,
      quantity: 1,
    })
    // After server response, items are synced
    expect(store.items).toHaveLength(2) // from mock response
  })

  it('should rollback on addItem failure', async () => {
    const { addToCart } = await import('@/api/cartApi')
    vi.mocked(addToCart).mockRejectedValueOnce(new Error('Server error'))

    const store = useCartStore()
    store.items = [{ productId: 1, productName: 'Existing', price: 10, quantity: 1, subtotal: 10 }]

    await expect(
      store.addItem({ productId: 2, productName: 'New', price: 20, quantity: 1 }),
    ).rejects.toThrow('Server error')

    expect(store.items).toHaveLength(1)
    expect(store.items[0]!.productId).toBe(1)
    expect(store.error).toBe('Server error')
  })

  it('should update quantity optimistically', async () => {
    const store = useCartStore()
    await store.fetchCart()
    await store.updateItemQuantity(1, 5)
    // After server response, items are synced
    expect(store.items).toHaveLength(2)
  })

  it('should rollback on updateQuantity failure', async () => {
    const { updateQuantity } = await import('@/api/cartApi')
    vi.mocked(updateQuantity).mockRejectedValueOnce(new Error('Server error'))

    const store = useCartStore()
    store.items = [{ productId: 1, productName: 'Product', price: 10, quantity: 2, subtotal: 20 }]

    await expect(store.updateItemQuantity(1, 5)).rejects.toThrow('Server error')

    expect(store.items[0]!.quantity).toBe(2)
    expect(store.items[0]!.subtotal).toBe(20)
  })

  it('should remove item optimistically', async () => {
    const store = useCartStore()
    store.items = [
      { productId: 1, productName: 'Product 1', price: 10, quantity: 1, subtotal: 10 },
      { productId: 2, productName: 'Product 2', price: 20, quantity: 1, subtotal: 20 },
    ]

    await store.removeCartItem(1)
    expect(store.items).toHaveLength(1)
    expect(store.items[0]!.productId).toBe(2)
  })

  it('should rollback on removeItem failure', async () => {
    const { removeItem } = await import('@/api/cartApi')
    vi.mocked(removeItem).mockRejectedValueOnce(new Error('Server error'))

    const store = useCartStore()
    store.items = [{ productId: 1, productName: 'Product', price: 10, quantity: 1, subtotal: 10 }]

    await expect(store.removeCartItem(1)).rejects.toThrow('Server error')
    expect(store.items).toHaveLength(1)
  })

  it('should increment existing item quantity on addItem', async () => {
    const store = useCartStore()
    store.items = [{ productId: 1, productName: 'Product', price: 10, quantity: 1, subtotal: 10 }]

    // Optimistic update should increment
    const addPromise = store.addItem({
      productId: 1,
      productName: 'Product',
      price: 10,
      quantity: 2,
    })
    // During optimistic update
    expect(store.items[0]!.quantity).toBe(3)
    await addPromise
  })

  it('should reset state', async () => {
    const store = useCartStore()
    await store.fetchCart()
    store.$reset()
    expect(store.items).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  // === Story 3.4: Cart Merge ===

  describe('mergeAnonymousCart', () => {
    it('shouldCallMergeApiWithAnonymousIdFromLocalStorage', async () => {
      const { mergeCart } = await import('@/api/cartApi')
      localStorage.setItem('robomart-user-id', 'anon-uuid-test')

      const store = useCartStore()
      await store.mergeAnonymousCart()

      expect(mergeCart).toHaveBeenCalledWith('anon-uuid-test')
    })

    it('shouldUpdateStoreWithMergedCartResponse', async () => {
      localStorage.setItem('robomart-user-id', 'anon-uuid-test')

      const store = useCartStore()
      await store.mergeAnonymousCart()

      expect(store.items).toHaveLength(3)
      expect(store.totalItems).toBe(6)
      expect(store.totalPrice).toBe(85.5)
    })

    it('shouldClearLocalStorageAfterSuccessfulMerge', async () => {
      localStorage.setItem('robomart-user-id', 'anon-uuid-test')

      const store = useCartStore()
      await store.mergeAnonymousCart()

      expect(localStorage.getItem('robomart-user-id')).toBeNull()
    })

    it('shouldClearAnonymousIdCacheAfterSuccessfulMerge', async () => {
      const { clearAnonymousIdCache } = await import('@/api/client')
      localStorage.setItem('robomart-user-id', 'anon-uuid-test')

      const store = useCartStore()
      await store.mergeAnonymousCart()

      expect(clearAnonymousIdCache).toHaveBeenCalled()
    })

    it('shouldNotCallMergeWhenNoAnonymousIdExists', async () => {
      const { mergeCart } = await import('@/api/cartApi')
      localStorage.removeItem('robomart-user-id')

      const store = useCartStore()
      await store.mergeAnonymousCart()

      expect(mergeCart).not.toHaveBeenCalled()
    })

    it('shouldHandleMergeFailureGracefully', async () => {
      const { mergeCart } = await import('@/api/cartApi')
      vi.mocked(mergeCart).mockRejectedValueOnce(new Error('Merge failed'))
      localStorage.setItem('robomart-user-id', 'anon-uuid-test')

      const store = useCartStore()
      // Should not throw
      await store.mergeAnonymousCart()

      // localStorage should NOT be cleared on failure
      expect(localStorage.getItem('robomart-user-id')).toBe('anon-uuid-test')
    })
  })
})
