import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderDetail, OrderStatus } from '@/types/order'
import OrderConfirmationView from '../OrderConfirmationView.vue'

const { orderStore, resetCheckout } = vi.hoisted(() => ({
  orderStore: {
    isLoading: false,
    currentOrder: null as OrderDetail | null,
    fetchOrder: vi.fn(),
  },
  resetCheckout: vi.fn(),
}))

vi.mock('@/stores/useOrderStore', () => ({ useOrderStore: () => orderStore }))
vi.mock('@/stores/useCheckoutStore', () => ({
  useCheckoutStore: () => ({ $reset: resetCheckout }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { orderId: '42' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

describe('OrderConfirmationView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    orderStore.currentOrder = {
      id: 42,
      createdAt: '2026-10-26T00:00:00Z',
      updatedAt: '2026-10-26T00:00:00Z',
      totalAmount: 10,
      status: 'CONFIRMED',
      shippingAddress: null,
      cancellationReason: null,
      items: [],
      statusHistory: [],
    }
  })

  it.each<[OrderStatus, string]>([
    ['INVENTORY_RELEASING', 'Order Cancellation in Progress'],
    ['CANCELLED', 'Order Cancelled'],
  ])('shows %s without promising confirmation or delivery', async (status, heading) => {
    orderStore.currentOrder!.status = status
    const wrapper = mount(OrderConfirmationView, {
      global: { stubs: { Button: true, Skeleton: true } },
    })
    await flushPromises()

    expect(wrapper.find('h1').text()).toBe(heading)
    expect(wrapper.text()).not.toContain('Order Confirmed!')
    expect(wrapper.text()).not.toContain('Estimated delivery')
    expect(orderStore.fetchOrder).toHaveBeenCalledWith(42)
  })

  it('continues to show confirmation and delivery for confirmed orders', async () => {
    const wrapper = mount(OrderConfirmationView, {
      global: { stubs: { Button: true, Skeleton: true } },
    })
    await flushPromises()

    expect(wrapper.find('h1').text()).toBe('Order Confirmed!')
    expect(wrapper.text()).toContain('Estimated delivery')
  })
})
