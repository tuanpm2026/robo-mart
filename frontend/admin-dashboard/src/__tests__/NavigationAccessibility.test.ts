import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Drawer from 'primevue/drawer'
import AdminLayout from '../layouts/AdminLayout.vue'
import SlideOverPanel from '../components/SlideOverPanel.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
async function layout(mobile = false) {
  let listener: (() => void) | undefined
  const media = {
    matches: mobile,
    addEventListener: (_: string, fn: () => void) => {
      listener = fn
    },
    removeEventListener: vi.fn(),
  }
  vi.stubGlobal('matchMedia', () => media)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/admin/dashboard', '/admin/orders', '/admin/products'].map((path) => ({
      path,
      component: { template: '<h1>Page content</h1>' },
    })),
  })
  await router.push('/admin/orders')
  await router.isReady()
  const wrapper = mount(AdminLayout, {
    attachTo: document.body,
    global: {
      plugins: [router, createPinia(), PrimeVue],
      stubs: {
        Toast: true,
        ConfirmDialog: true,
        CommandPalette: true,
        Menu: true,
        ConnectionStatus: true,
      },
    },
  })
  wrappers.push(wrapper)
  return {
    wrapper,
    router,
    resize: async (mobile: boolean) => {
      media.matches = mobile
      listener?.()
      await flushPromises()
    },
  }
}

describe('Administration navigation access', () => {
  it('retains link names and expanded state when desktop navigation collapses', async () => {
    const { wrapper } = await layout()
    const toggle = wrapper.get('button[aria-label="Collapse sidebar"]')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    const links = wrapper.findAll('nav a')
    expect(links.map((link) => link.attributes('aria-label'))).toEqual([
      'Dashboard',
      'Orders',
      'Products',
      'Inventory',
      'Health',
      'Unprocessed Events',
      'Reports',
    ])
    expect(links.every((link) => !!link.text())).toBe(true)
    expect(wrapper.get('.skip-to-main').attributes('href')).toBe('#admin-main-content')
  })

  it('opens a named mobile dialog, restores focus on close and closes on route/resize', async () => {
    const { wrapper, router, resize } = await layout(true)
    expect(wrapper.find('.admin-sidebar').exists()).toBe(false)
    const toggle = wrapper.get('button[aria-label="Open navigation"]')
    await toggle.trigger('click')
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('true')
    const drawer = wrapper.findComponent(Drawer)
    expect(drawer.props('header')).toBe('RoboMart Admin')
    expect(document.querySelector('[role=dialog]')?.getAttribute('aria-label')).toBe(
      'Administration navigation',
    )
    expect(drawer.props('visible')).toBe(true)
    drawer.vm.$emit('update:visible', false)
    drawer.vm.$emit('after-hide')
    await flushPromises()
    expect(document.activeElement).toBe(toggle.element)
    await toggle.trigger('click')
    await router.push('/admin/products')
    await flushPromises()
    expect(drawer.props('visible')).toBe(false)
    await toggle.trigger('click')
    await resize(false)
    expect(drawer.props('visible')).toBe(false)
    drawer.vm.$emit('after-hide')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('main').element)
    expect(wrapper.findAll('.admin-sidebar')).toHaveLength(1)
    expect(wrapper.find('button[aria-label="Open navigation"]').exists()).toBe(false)
  })

  it('restores a detail drawer trigger after close without focusing a removed element', async () => {
    const button = document.createElement('button')
    button.textContent = 'View order details'
    document.body.append(button)
    const wrapper = mount(SlideOverPanel, {
      props: { visible: false, title: 'Order #42' },
      attachTo: document.body,
      global: { plugins: [PrimeVue] },
    })
    wrappers.push(wrapper)
    button.focus()
    await wrapper.setProps({ visible: true })
    const drawer = wrapper.findComponent(Drawer)
    await flushPromises()
    expect(document.querySelector('[role=dialog]')?.getAttribute('aria-label')).toBe('Order #42')
    document.body.focus()
    await wrapper.setProps({ visible: false })
    drawer.vm.$emit('after-hide')
    expect(document.activeElement).toBe(button)
    button.remove()
    drawer.vm.$emit('after-hide')
    expect(document.activeElement).not.toBe(button)
  })
})
