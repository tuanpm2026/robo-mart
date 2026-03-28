import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import SearchBar from '../SearchBar.vue'

vi.mock('@/api/productApi', () => ({
  searchProducts: vi.fn().mockResolvedValue({
    data: [
      { id: 1, name: 'Wireless Mouse', price: 29.99, primaryImageUrl: '/img.jpg', sku: 'M1', rating: 4, brand: 'B', stockQuantity: 10, categoryName: 'Electronics' },
    ],
    pagination: { page: 0, size: 5, totalElements: 1, totalPages: 1 },
    traceId: '',
  }),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: { template: '<div />' } },
      { path: '/products/:id', component: { template: '<div />' } },
    ],
  })
}

describe('SearchBar', () => {
  it('should render search bar container', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(SearchBar, {
      global: { plugins: [router, PrimeVue] },
    })

    expect(wrapper.find('.search-bar').exists()).toBe(true)
  })

  it('should render AutoComplete component', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(SearchBar, {
      global: { plugins: [router, PrimeVue] },
    })

    expect(wrapper.findComponent({ name: 'AutoComplete' }).exists()).toBe(true)
  })

  it('should have search placeholder text', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(SearchBar, {
      global: { plugins: [router, PrimeVue] },
    })

    const input = wrapper.find('input')
    expect(input.attributes('placeholder')).toBe('Search products...')
  })
})
