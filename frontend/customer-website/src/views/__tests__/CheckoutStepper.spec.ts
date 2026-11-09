import { expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import CheckoutView from '../CheckoutView.vue'
import { useCartStore } from '@/stores/useCartStore'

// Keep the real PrimeVue stepper and cart review button: this guards ownership,
// tab/panel associations and selected state as checkout advances.
it('exposes checkout tabs as direct children of a named tablist and selects the current panel', async () => {
  const pinia = createPinia()
  useCartStore(pinia).items = [
    { productId: 1, productName: 'Fixture', quantity: 1, price: 10, subtotal: 10 },
  ]
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/checkout', component: CheckoutView }],
  })
  await router.push('/checkout')
  await router.isReady()
  const wrapper = mount(CheckoutView, {
    global: { plugins: [pinia, router, PrimeVue, ToastService] },
  })
  await flushPromises()
  const list = wrapper.get('[role="tablist"][aria-label="Checkout steps"]')
  const tabs = list.findAll('[role="tab"]')
  expect(tabs).toHaveLength(4)
  expect([...list.element.children].every((child) => child.getAttribute('role') === 'tab')).toBe(
    true,
  )
  expect(tabs.map((tab) => tab.attributes('aria-selected'))).toEqual([
    'true',
    'false',
    'false',
    'false',
  ])
  for (const tab of tabs) {
    const panel = wrapper.get('#' + tab.attributes('aria-controls'))
    expect(panel.attributes('aria-labelledby')).toBe(tab.attributes('id'))
    expect(wrapper.find('#' + panel.attributes('aria-controls')).exists()).toBe(true)
  }
  await wrapper.get('button.scr__continue').trigger('click')
  await flushPromises()
  expect(tabs.map((tab) => tab.attributes('aria-selected'))).toEqual([
    'false',
    'true',
    'false',
    'false',
  ])
  expect(wrapper.get('#checkout-panel-2').isVisible()).toBe(true)
  wrapper.unmount()
})
