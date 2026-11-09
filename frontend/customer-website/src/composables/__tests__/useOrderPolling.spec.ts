import { defineComponent } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useOrderPolling } from '../useOrderPolling'
import { useOrderStore } from '@/stores/useOrderStore'
import type { OrderDetail } from '@/types/order'
const { getOrder, reconcileCart } = vi.hoisted(() => ({
  getOrder: vi.fn(),
  reconcileCart: vi.fn(),
}))
vi.mock('@/api/orderApi', () => ({ getOrder, getOrders: vi.fn(), cancelOrder: vi.fn() }))
vi.mock('@/stores/useCheckoutStore', () => ({ useCheckoutStore: () => ({ reconcileCart }) }))
const Harness = defineComponent({
  setup() {
    useOrderPolling(() => 42)
    return () => null
  },
})
const detail = (status: OrderDetail['status']) => ({ id: 42, status }) as OrderDetail
describe('bounded order polling', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.resetAllMocks()
  })
  afterEach(() => vi.useRealTimers())
  it('follows pending to confirmed without clearing the visible order between polls', async () => {
    const orders = useOrderStore()
    orders.currentOrder = detail('PENDING')
    getOrder
      .mockResolvedValueOnce({ data: detail('PENDING') })
      .mockResolvedValueOnce({ data: detail('CONFIRMED') })
    const wrapper = mount(Harness)
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(orders.currentOrder?.status).toBe('PENDING')
    expect(orders.isLoading).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(reconcileCart).toHaveBeenLastCalledWith(detail('CONFIRMED'))
    await vi.advanceTimersByTimeAsync(10000)
    expect(getOrder).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('stops on unmount and resumes cancellation from a previously confirmed order', async () => {
    const orders = useOrderStore()
    orders.currentOrder = detail('CONFIRMED')
    getOrder.mockResolvedValue({ data: detail('CANCELLATION_PENDING') })
    const wrapper = mount(Harness)
    await vi.advanceTimersByTimeAsync(2000)
    expect(getOrder).not.toHaveBeenCalled()
    orders.currentOrder = detail('CANCELLATION_PENDING')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(getOrder).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(6000)
    expect(getOrder).toHaveBeenCalledTimes(1)
  })
  it('backs off failed reads while preserving the pending order', async () => {
    const orders = useOrderStore()
    orders.currentOrder = detail('PENDING')
    getOrder
      .mockRejectedValueOnce(new Error('unavailable'))
      .mockResolvedValueOnce({ data: detail('CONFIRMED') })
    const wrapper = mount(Harness)
    await vi.advanceTimersByTimeAsync(2000)
    expect(getOrder).toHaveBeenCalledTimes(1)
    expect(orders.currentOrder?.status).toBe('PENDING')
    await vi.advanceTimersByTimeAsync(2000)
    expect(getOrder).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(2000)
    expect(getOrder).toHaveBeenCalledTimes(2)
    expect(orders.currentOrder?.status).toBe('CONFIRMED')
    wrapper.unmount()
  })

  it('stops after sixty polls and preserves the last pending state', async () => {
    const orders = useOrderStore()
    orders.currentOrder = detail('PENDING')
    getOrder.mockResolvedValue({ data: detail('PENDING') })
    const wrapper = mount(Harness)
    await vi.advanceTimersByTimeAsync(125000)
    expect(getOrder).toHaveBeenCalledTimes(60)
    expect(orders.currentOrder?.status).toBe('PENDING')
    wrapper.unmount()
  })
})
