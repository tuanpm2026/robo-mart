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
const mockCartReset = vi.fn()
vi.mock('@/stores/useCartStore', () => ({
  useCartStore: () => ({
    items: [{ productId: 1, productName: 'Widget', price: 10, quantity: 2, subtotal: 20 }],
    $reset: mockCartReset,
  }),
}))

import { useCheckoutStore } from '@/stores/useCheckoutStore'
import { placeOrder as mockPlaceOrder } from '@/api/orderApi'

describe('useCheckoutStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
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
    const data = { fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' }
    store.setShippingData(data)
    expect(store.shippingData).toEqual(data)
  })

  it('placeOrder succeeds: navigates to confirmation and resets cart', async () => {
    const store = useCheckoutStore()
    store.setShippingData({ fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' })

    vi.mocked(mockPlaceOrder).mockResolvedValue({
      data: { id: 42, status: 'CONFIRMED', createdAt: '', totalAmount: 20, itemCount: 1, cancellationReason: null },
      traceId: 'trace-1',
    })

    await store.placeOrder()

    expect(mockPush).toHaveBeenCalledWith('/order-confirmation/42')
    expect(mockCartReset).toHaveBeenCalled()
    expect(store.isPlacingOrder).toBe(false)
    expect(store.error).toBeNull()
  })

  it('placeOrder sets PAYMENT_FAILED and goes to step 3 on payment error', async () => {
    const store = useCheckoutStore()
    store.setShippingData({ fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' })

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
    store.setShippingData({ fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' })

    vi.mocked(mockPlaceOrder).mockRejectedValue({
      response: { data: { error: { code: 'ORDER_INVENTORY_FAILED', message: 'Insufficient stock' } } },
    })

    await store.placeOrder()

    expect(store.error?.type).toBe('INVENTORY_FAILED')
    expect(store.isPlacingOrder).toBe(false)
  })

  it('placeOrder sets UNKNOWN error on unexpected failure', async () => {
    const store = useCheckoutStore()
    store.setShippingData({ fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' })

    vi.mocked(mockPlaceOrder).mockRejectedValue(new Error('Network error'))

    await store.placeOrder()

    expect(store.error?.type).toBe('UNKNOWN')
    expect(store.isPlacingOrder).toBe(false)
  })

  it('$reset restores initial state', () => {
    const store = useCheckoutStore()
    store.nextStep()
    store.nextStep()
    store.setShippingData({ fullName: 'Jane', street: '123 St', city: 'SF', state: 'CA', postalCode: '12345', country: 'US' })
    store.$reset()
    expect(store.currentStep).toBe(1)
    expect(store.shippingData).toBeNull()
    expect(store.error).toBeNull()
    expect(store.isPlacingOrder).toBe(false)
  })
})
