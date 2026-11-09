import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import PrimeVue from 'primevue/config'
import Dialog from 'primevue/dialog'
import SagaProgressOverlay from './SagaProgressOverlay.vue'
import { useCheckoutStore } from '@/stores/useCheckoutStore'

it('focuses processing status on open and the visible error before the earlier heading on close', async () => {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()
  const store = useCheckoutStore(pinia)
  const wrapper = mount(SagaProgressOverlay, {
    attachTo: document.body,
    global: { plugins: [pinia, router, PrimeVue] },
  })
  store.isPlacingOrder = true
  await flushPromises()
  const dialog = wrapper.findComponent(Dialog)
  expect(dialog.props('closable')).toBe(false)
  expect(dialog.props('closeOnEscape')).toBe(false)
  dialog.vm.$emit('show')
  await flushPromises()
  const status = document.querySelector<HTMLElement>('.spo')!
  expect(document.activeElement).toBe(status)
  expect(status.textContent).toContain('Submitting your order')
  expect(status.textContent).not.toContain('Reserving')

  const checkout = document.createElement('div')
  checkout.className = 'checkout'
  checkout.innerHTML = '<h2>Payment</h2><div role="alert">Check your orders before retrying.</div>'
  document.body.append(checkout)
  const heading = checkout.querySelector('h2')!
  const alert = checkout.querySelector<HTMLElement>('[role="alert"]')!
  // jsdom has no layout. Browser evidence separately verifies real visibility,
  // focus traps and Escape; here both result targets have rendered rectangles.
  for (const target of [heading, alert])
    vi.spyOn(target, 'getClientRects').mockReturnValue([
      new DOMRect(0, 0, 100, 20),
    ] as unknown as DOMRectList)
  store.isPlacingOrder = false
  await flushPromises()
  dialog.vm.$emit('after-hide')
  expect(document.activeElement).toBe(alert)
  wrapper.unmount()
  checkout.remove()
})
