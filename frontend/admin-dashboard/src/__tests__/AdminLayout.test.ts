import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'
import AdminLayout from '../layouts/AdminLayout.vue'

function createTestSetup() {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/dashboard', component: { template: '<div />' } },
      { path: '/admin/orders', component: { template: '<div />' } },
      { path: '/admin/products', component: { template: '<div />' } },
      { path: '/admin/inventory', component: { template: '<div />' } },
    ],
  })
  return { pinia, router }
}

describe('AdminLayout', () => {
  it('renders Operations section in sidebar', async () => {
    const { pinia, router } = createTestSetup()
    await router.push('/admin/dashboard')
    await router.isReady()

    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [pinia, router, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
      },
    })

    expect(wrapper.text()).toContain('Operations')
  })

  it('renders Dashboard, Orders, Products, Inventory nav links', async () => {
    const { pinia, router } = createTestSetup()
    await router.push('/admin/dashboard')
    await router.isReady()

    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [pinia, router, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
      },
    })

    const text = wrapper.text()
    expect(text).toContain('Dashboard')
    expect(text).toContain('Orders')
    expect(text).toContain('Products')
    expect(text).toContain('Inventory')
  })

  it('renders breadcrumb area in top header', async () => {
    const { pinia, router } = createTestSetup()
    await router.push('/admin/dashboard')
    await router.isReady()

    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [pinia, router, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
      },
    })

    expect(wrapper.find('.admin-topbar__breadcrumb').exists()).toBe(true)
  })

  it('renders ⌘K hint button in top header', async () => {
    const { pinia, router } = createTestSetup()
    await router.push('/admin/dashboard')
    await router.isReady()

    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [pinia, router, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
      },
    })

    expect(wrapper.find('.admin-cmd-btn').exists()).toBe(true)
    expect(wrapper.find('.admin-cmd-btn').text()).toContain('⌘K')
  })

  it('toggles sidebar collapsed state when toggle button is clicked', async () => {
    const { pinia, router } = createTestSetup()
    await router.push('/admin/dashboard')
    await router.isReady()

    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [pinia, router, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
      },
    })

    const sidebar = wrapper.find('.admin-sidebar')
    expect(sidebar.classes()).not.toContain('admin-sidebar--collapsed')

    await wrapper.find('.admin-sidebar__toggle').trigger('click')
    expect(sidebar.classes()).toContain('admin-sidebar--collapsed')
  })
})
