import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import CartSummary from '../CartSummary.vue'

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/cart', component: { template: '<div />' } },
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
      directives: { tooltip: Tooltip },
    },
  })
}

describe('CartSummary', () => {
  it('should render total items count', async () => {
    const wrapper = await mountSummary(3, 45.5)
    expect(wrapper.text()).toContain('Items (3)')
  })

  it('should render total price', async () => {
    const wrapper = await mountSummary(3, 45.5)
    expect(wrapper.text()).toContain('$45.50')
  })

  it('should have disabled checkout button', async () => {
    const wrapper = await mountSummary()
    const checkoutBtn = wrapper.find('.cart-summary__checkout-btn')
    expect(checkoutBtn.exists()).toBe(true)
    expect(checkoutBtn.attributes('disabled')).toBeDefined()
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
        directives: { tooltip: Tooltip },
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
