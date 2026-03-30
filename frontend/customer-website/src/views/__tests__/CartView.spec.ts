import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import CartView from '../CartView.vue'

vi.mock('@/api/cartApi', () => ({
  getCart: vi.fn().mockResolvedValue({
    data: {
      cartId: 'cart-123',
      items: [
        { productId: 1, productName: 'Product 1', price: 10.0, quantity: 2, subtotal: 20.0 },
      ],
      totalItems: 2,
      totalPrice: 20.0,
    },
    traceId: 'trace-1',
  }),
  addToCart: vi.fn(),
  updateQuantity: vi.fn(),
  removeItem: vi.fn().mockResolvedValue(undefined),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/cart', component: { template: '<div />' } },
    ],
  })
}

async function mountCartView() {
  const router = createTestRouter()
  const pinia = createPinia()
  await router.push('/cart')
  await router.isReady()
  return mount(CartView, {
    global: {
      plugins: [router, pinia, PrimeVue, ToastService],
      directives: { tooltip: Tooltip },
    },
  })
}

describe('CartView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should render page title', async () => {
    const wrapper = await mountCartView()
    expect(wrapper.find('.cart__title').text()).toBe('Shopping Cart')
  })

  it('should show loading skeleton initially', async () => {
    const wrapper = await mountCartView()
    expect(wrapper.find('.cart__skeleton').exists()).toBe(true)
  })

  it('should show cart items after loading', async () => {
    const wrapper = await mountCartView()
    await flushPromises()
    expect(wrapper.find('.cart__content').exists()).toBe(true)
    expect(wrapper.findAll('.cart-item')).toHaveLength(1)
  })

  it('should show empty state when cart is empty', async () => {
    const { getCart } = await import('@/api/cartApi')
    vi.mocked(getCart).mockResolvedValueOnce({
      data: { cartId: 'cart-123', items: [], totalItems: 0, totalPrice: 0 },
      traceId: 'trace-2',
    })

    const wrapper = await mountCartView()
    await flushPromises()
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  it('should render cart summary', async () => {
    const wrapper = await mountCartView()
    await flushPromises()
    expect(wrapper.find('.cart-summary').exists()).toBe(true)
  })
})
