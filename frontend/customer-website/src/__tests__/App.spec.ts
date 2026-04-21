import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import App from '../App.vue'

vi.mock('@/api/productApi', () => ({
  searchProducts: vi.fn().mockResolvedValue({
    data: [],
    pagination: { page: 0, size: 5, totalElements: 0, totalPages: 0 },
    traceId: '',
  }),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div>Home</div>' } }],
  })
}

describe('App', () => {
  it('should render skip-to-main-content link', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router, pinia, PrimeVue, ToastService],
      },
    })

    const skipLink = wrapper.find('a.skip-to-main')
    expect(skipLink.exists()).toBe(true)
    expect(skipLink.attributes('href')).toBe('#main-content')
    expect(skipLink.text()).toBe('Skip to main content')
  })

  it('should render Toast component', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router, pinia, PrimeVue, ToastService],
      },
    })

    expect(wrapper.findComponent({ name: 'Toast' }).exists()).toBe(true)
  })

  it('should render DefaultLayout', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router, pinia, PrimeVue, ToastService],
      },
    })

    expect(wrapper.find('header.header').exists()).toBe(true)
    expect(wrapper.find('main#main-content').exists()).toBe(true)
    expect(wrapper.find('footer.footer').exists()).toBe(true)
  })
})
