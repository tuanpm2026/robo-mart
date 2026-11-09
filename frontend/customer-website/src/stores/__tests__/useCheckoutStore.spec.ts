import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock vue-router before any imports
const mockPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
}))

// Mock orderApi
vi.mock('@/api/orderApi', () => ({
  placeOrder: vi.fn(),
  getOrders: vi.fn(),
  getOrder: vi.fn(),
  cancelOrder: vi.fn(),
}))

// Mock useCartStore
const mockCompleteCheckout = vi.fn().mockResolvedValue(undefined)
const cartItems = [
  { productId: 1, productName: 'Widget', price: 10, quantity: 2, subtotal: 20, lineId: 'line-1' },
]
let userId = 'customer-a'
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: userId } }),
}))
vi.mock('@/stores/useCartStore', () => ({
  useCartStore: () => ({
    items: cartItems,
    fetchCart: vi.fn(),
    completeCheckout: mockCompleteCheckout,
  }),
}))

import { useCheckoutStore } from '@/stores/useCheckoutStore'
import { placeOrder as mockPlaceOrder } from '@/api/orderApi'

describe('useCheckoutStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    sessionStorage.clear()
    cartItems[0]!.quantity = 2
    cartItems[0]!.subtotal = 20
    userId = 'customer-a'
    mockCompleteCheckout.mockResolvedValue(undefined)
  })

  it('has correct initial state', () => {
    const store = useCheckoutStore()
    expect(store.currentStep).toBe(1)
    expect(store.shippingData).toBeNull()
    expect(store.paymentData).toBeNull()
    expect(store.isPlacingOrder).toBe(false)
    expect(store.error).toBeNull()
  })

  it('nextStep increments up to max 4', () => {
    const store = useCheckoutStore()
    store.nextStep()
    expect(store.currentStep).toBe(2)
    store.nextStep()
    store.nextStep()
    expect(store.currentStep).toBe(4)
    store.nextStep()
    expect(store.currentStep).toBe(4)
  })

  it('prevStep decrements down to min 1', () => {
    const store = useCheckoutStore()
    store.nextStep()
    store.nextStep()
    store.prevStep()
    expect(store.currentStep).toBe(2)
    store.prevStep()
    store.prevStep()
    expect(store.currentStep).toBe(1)
  })

  it('setShippingData stores shipping form data', () => {
    const store = useCheckoutStore()
    const data = {
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    }
    store.setShippingData(data)
    expect(store.shippingData).toEqual(data)
  })

  it('placeOrder acknowledges the purchased snapshot and navigates to confirmation', async () => {
    const store = useCheckoutStore()
    store.setShippingData({
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    })
    store.setPaymentData({
      cardholderName: 'Jane',
      cardNumber: '4242424242424242',
      expiry: '12/99',
      cvv: '123',
    })

    vi.mocked(mockPlaceOrder).mockResolvedValue({
      data: {
        id: 42,
        status: 'CONFIRMED',
        createdAt: '',
        totalAmount: 20,
        itemCount: 1,
        cancellationReason: null,
      },
      traceId: 'trace-1',
    })

    await store.placeOrder()

    expect(mockPush).toHaveBeenCalledWith('/order-confirmation/42')
    expect(mockCompleteCheckout).toHaveBeenCalledWith(42, [
      { productId: 1, quantity: 2, lineId: 'line-1' },
    ])
    expect(store.isPlacingOrder).toBe(false)
    expect(store.error).toBeNull()
  })

  it('placeOrder sets PAYMENT_FAILED and goes to step 3 on payment error', async () => {
    const store = useCheckoutStore()
    store.setShippingData({
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    })
    store.setPaymentData({
      cardholderName: 'Jane',
      cardNumber: '4242424242424242',
      expiry: '12/99',
      cvv: '123',
    })

    vi.mocked(mockPlaceOrder).mockRejectedValue({
      response: { data: { error: { code: 'ORDER_PAYMENT_FAILED', message: 'Payment declined' } } },
    })

    await store.placeOrder()

    expect(store.error?.type).toBe('PAYMENT_FAILED')
    expect(store.currentStep).toBe(3)
    expect(store.isPlacingOrder).toBe(false)
    expect(mockPush).not.toHaveBeenCalled()
  })

  it('placeOrder sets INVENTORY_FAILED on inventory error', async () => {
    const store = useCheckoutStore()
    store.setShippingData({
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    })
    store.setPaymentData({
      cardholderName: 'Jane',
      cardNumber: '4242424242424242',
      expiry: '12/99',
      cvv: '123',
    })

    vi.mocked(mockPlaceOrder).mockRejectedValue({
      response: {
        data: { error: { code: 'ORDER_INVENTORY_FAILED', message: 'Insufficient stock' } },
      },
    })

    await store.placeOrder()

    expect(store.error?.type).toBe('INVENTORY_FAILED')
    expect(store.isPlacingOrder).toBe(false)
  })

  it('placeOrder sets UNKNOWN error on unexpected failure', async () => {
    const store = useCheckoutStore()
    store.setShippingData({
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    })
    store.setPaymentData({
      cardholderName: 'Jane',
      cardNumber: '4242424242424242',
      expiry: '12/99',
      cvv: '123',
    })

    vi.mocked(mockPlaceOrder).mockRejectedValue(new Error('Network error'))

    await store.placeOrder()

    expect(store.error?.type).toBe('UNKNOWN')
    expect(store.isPlacingOrder).toBe(false)
  })

  it('$reset restores initial state', () => {
    const store = useCheckoutStore()
    store.nextStep()
    store.nextStep()
    store.setShippingData({
      fullName: 'Jane',
      street: '123 St',
      city: 'SF',
      state: 'CA',
      postalCode: '12345',
      country: 'US',
    })
    store.$reset()
    expect(store.currentStep).toBe(1)
    expect(store.shippingData).toBeNull()
    expect(store.error).toBeNull()
    expect(store.isPlacingOrder).toBe(false)
  })
})

function prepareCheckout() {
  const store = useCheckoutStore()
  store.setShippingData({
    fullName: 'Jane',
    street: '123 St',
    city: 'SF',
    state: 'CA',
    postalCode: '12345',
    country: 'US',
  })
  store.setPaymentData({
    cardholderName: 'Jane',
    cardNumber: '4242424242424242',
    expiry: '12/99',
    cvv: '123',
  })
  return store
}
const confirmed = {
  data: {
    id: 42,
    status: 'CONFIRMED' as const,
    createdAt: '',
    totalAmount: 20,
    itemCount: 1,
    cancellationReason: null,
  },
  traceId: '',
}

describe('checkout retry and cleanup recovery', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    sessionStorage.clear()
    userId = 'customer-a'
    cartItems[0]!.quantity = 2
    cartItems[0]!.subtotal = 20
    mockCompleteCheckout.mockResolvedValue(undefined)
  })

  it('replays the same key and original payload after response loss, reload and a cart change', async () => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockRejectedValueOnce(new Error('response lost'))
    await store.placeOrder()
    const first = vi.mocked(mockPlaceOrder).mock.calls[0]!
    expect(first[1]).toMatch(/^[0-9a-f-]{36}$/)
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    const saved = sessionStorage.getItem('robomart-checkout:customer-a')!
    expect(saved).not.toContain('4242424242424242')
    expect(saved).not.toContain('cvv')
    cartItems[0]!.quantity = 5
    setActivePinia(createPinia()) // simulate a newly loaded page
    const restored = useCheckoutStore()
    restored.restoreAttempt()
    expect(restored.currentStep).toBe(4)
    expect(restored.paymentData).toBeNull()
    vi.mocked(mockPlaceOrder).mockResolvedValueOnce(confirmed)
    await restored.placeOrder()
    expect(vi.mocked(mockPlaceOrder).mock.calls[1]).toEqual(first)
    expect(mockCompleteCheckout).toHaveBeenCalledWith(42, [
      { productId: 1, quantity: 2, lineId: 'line-1' },
    ])
    expect(sessionStorage.getItem('robomart-checkout:customer-a')).toBeNull()
  })

  it('does not create an order when persisting the retry key fails', async () => {
    const store = prepareCheckout()
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    await store.placeOrder()
    expect(mockPlaceOrder).not.toHaveBeenCalled()
    expect(store.error?.type).toBe('UNKNOWN')
    blocked.mockRestore()
  })

  it('allows checkout edits after validation rejects a request before order creation', async () => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockRejectedValueOnce({ response: { status: 400, data: {} } })
    await store.placeOrder()
    expect(store.hasPendingAttempt).toBe(false)
    expect(sessionStorage.getItem('robomart-checkout:customer-a')).toBeNull()
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    expect(store.error?.message).toContain('review your checkout details')
  })

  it('uses a new key after a definitive decline and leaves cart intact', async () => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockRejectedValueOnce({
      response: { data: { error: { code: 'ORDER_PAYMENT_FAILED' } } },
    })
    await store.placeOrder()
    const firstKey = vi.mocked(mockPlaceOrder).mock.calls[0]![1]
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    vi.mocked(mockPlaceOrder).mockResolvedValueOnce(confirmed)
    await store.placeOrder()
    expect(vi.mocked(mockPlaceOrder).mock.calls[1]![1]).not.toBe(firstKey)
  })

  it('keeps accepted orders successful when cart acknowledgement fails and retries cleanup after reload', async () => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockResolvedValueOnce(confirmed)
    mockCompleteCheckout.mockRejectedValueOnce(new Error('Redis unavailable'))
    await store.placeOrder()
    expect(store.error).toBeNull()
    expect(store.cartCleanupPending).toBe(true)
    expect(mockPush).toHaveBeenCalledWith('/order-confirmation/42')
    setActivePinia(createPinia())
    await useCheckoutStore().reconcileCart(confirmed.data)
    expect(mockPlaceOrder).toHaveBeenCalledTimes(1)
    expect(mockCompleteCheckout).toHaveBeenCalledTimes(2)
    expect(sessionStorage.getItem('robomart-checkout:customer-a')).toBeNull()
  })

  it.each(['PENDING', 'PAYMENT_PENDING', 'CANCELLATION_PENDING', 'INVENTORY_RELEASING'] as const)(
    'retains the cart snapshot and retry identity while %s',
    async (status) => {
      const store = prepareCheckout()
      vi.mocked(mockPlaceOrder).mockResolvedValueOnce({
        ...confirmed,
        data: { ...confirmed.data, status },
      })
      await store.placeOrder()
      expect(mockCompleteCheckout).not.toHaveBeenCalled()
      expect(JSON.parse(sessionStorage.getItem('robomart-checkout:customer-a')!).orderId).toBe(42)
      await store.reconcileCart(confirmed.data)
      expect(mockCompleteCheckout).toHaveBeenCalledTimes(1)
    },
  )

  it.each(['CANCELLED'] as const)('preserves the cart for %s orders', async (status) => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockResolvedValueOnce({
      ...confirmed,
      data: { ...confirmed.data, status },
    })
    await store.placeOrder()
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('robomart-checkout:customer-a')).toBeNull()
  })

  it('isolates saved checkouts by authenticated user and ignores unrelated confirmation URLs', async () => {
    const store = prepareCheckout()
    vi.mocked(mockPlaceOrder).mockRejectedValueOnce(new Error('response lost'))
    await store.placeOrder()
    userId = 'customer-b'
    store.restoreAttempt()
    expect(store.hasPendingAttempt).toBe(false)
    await store.reconcileCart(confirmed.data)
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    userId = 'customer-a'
    await store.reconcileCart({ id: 999, status: 'CONFIRMED' })
    expect(mockCompleteCheckout).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('robomart-checkout:customer-a')).not.toBeNull()
  })
})
