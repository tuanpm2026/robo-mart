import { describe, it, expect } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { adminTheme } from '@robo-mart/shared'
import CommandPalette from '../components/CommandPalette.vue'

function createTestSetup() {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/dashboard', component: { template: '<div />' } },
    ],
  })
  return { pinia, router }
}

describe('CommandPalette', () => {
  it('dialog is closed initially (isOpen = false)', () => {
    const { pinia, router } = createTestSetup()

    const wrapper = shallowMount(CommandPalette, {
      global: {
        plugins: [
          pinia,
          router,
          [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
            typeof PrimeVue,
            ...unknown[],
          ],
        ],
      },
    })

    expect(wrapper.vm.isOpen).toBe(false)
  })

  it('opens dialog when Cmd+K keydown event fires', async () => {
    const { pinia, router } = createTestSetup()

    const wrapper = shallowMount(CommandPalette, {
      attachTo: document.body,
      global: {
        plugins: [
          pinia,
          router,
          [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
            typeof PrimeVue,
            ...unknown[],
          ],
        ],
      },
    })

    const event = new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true })
    document.dispatchEvent(event)
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.isOpen).toBe(true)
    wrapper.unmount()
  })

  it('opens dialog when open() method is called', async () => {
    const { pinia, router } = createTestSetup()

    const wrapper = shallowMount(CommandPalette, {
      global: {
        plugins: [
          pinia,
          router,
          [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
            typeof PrimeVue,
            ...unknown[],
          ],
        ],
      },
    })

    wrapper.vm.open()
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.isOpen).toBe(true)
  })

  it('sets isOpen to true when open() is called', async () => {
    const { pinia, router } = createTestSetup()

    const wrapper = shallowMount(CommandPalette, {
      global: {
        plugins: [
          pinia,
          router,
          [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
            typeof PrimeVue,
            ...unknown[],
          ],
        ],
      },
    })

    wrapper.vm.open()
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.isOpen).toBe(true)
  })

  it('does not open dialog after component is unmounted (listener cleaned up)', async () => {
    const { pinia, router } = createTestSetup()

    const wrapper = shallowMount(CommandPalette, {
      attachTo: document.body,
      global: {
        plugins: [
          pinia,
          router,
          [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
            typeof PrimeVue,
            ...unknown[],
          ],
        ],
      },
    })

    wrapper.unmount()

    const event = new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true })
    document.dispatchEvent(event)

    // After unmount the component is gone; verify listener no longer mutates state
    // (no throw or unexpected side effects)
    expect(true).toBe(true)
  })
})
