import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import CartSummary from '../CartSummary.vue'

// Mock useAuthStore
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    isAuthenticated: false,
    login: vi.fn(),
  }),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/cart', component: { template: '<div />' } },
      { path: '/checkout', component: { template: '<div />' } },
    ],
  })
}

async function mountSummary(totalItems = 3, totalPrice = 45.5) {
  const router = createTestRouter()
  await router.push('/cart')
  await router.isReady()
  return mount(CartSummary, {
    props: { totalItems, totalPrice },
    global: {
      plugins: [router, PrimeVue],
    },
  })
}

describe('CartSummary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should render total items count', async () => {
    const wrapper = await mountSummary(3, 45.5)
    expect(wrapper.text()).toContain('Items (3)')
  })

  it('should render total price', async () => {
    const wrapper = await mountSummary(3, 45.5)
    expect(wrapper.text()).toContain('$45.50')
  })

  it('should render an enabled checkout button', async () => {
    const wrapper = await mountSummary()
    const checkoutBtn = wrapper.find('.cart-summary__checkout-btn')
    expect(checkoutBtn.exists()).toBe(true)
    expect(checkoutBtn.attributes('disabled')).toBeUndefined()
  })

  it('should navigate home on continue shopping click', async () => {
    const router = createTestRouter()
    await router.push('/cart')
    await router.isReady()
    const push = vi.spyOn(router, 'push')

    const wrapper = mount(CartSummary, {
      props: { totalItems: 3, totalPrice: 45.5 },
      global: {
        plugins: [router, PrimeVue],
      },
    })

    await wrapper.find('.cart-summary__continue-btn').trigger('click')
    expect(push).toHaveBeenCalledWith('/')
  })

  it('should have accessible label', async () => {
    const wrapper = await mountSummary()
    expect(wrapper.find('.cart-summary').attributes('aria-label')).toBe('Cart summary')
  })
})
