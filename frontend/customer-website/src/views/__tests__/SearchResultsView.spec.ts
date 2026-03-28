import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import SearchResultsView from '../SearchResultsView.vue'

vi.mock('@/api/productApi', () => ({
  searchProducts: vi.fn().mockResolvedValue({
    data: [
      { id: 1, sku: 'P1', name: 'Mouse', price: 29, rating: 4, brand: 'Logitech', stockQuantity: 50, categoryName: 'Electronics', primaryImageUrl: '/1.jpg' },
    ],
    pagination: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
    traceId: '',
  }),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: SearchResultsView },
      { path: '/products/:id', component: { template: '<div />' } },
    ],
  })
}

describe('SearchResultsView', () => {
  it('should render search results title with keyword', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/search?keyword=mouse')
    await router.isReady()

    const wrapper = mount(SearchResultsView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.search-results__title').text()).toContain('mouse')
  })

  it('should render filter sidebar', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/search?keyword=mouse')
    await router.isReady()

    const wrapper = mount(SearchResultsView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.filter-sidebar').exists()).toBe(true)
  })

  it('should render product cards', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/search?keyword=mouse')
    await router.isReady()

    const wrapper = mount(SearchResultsView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.findAll('.product-card').length).toBe(1)
  })

  it('should show empty state when no results', async () => {
    const { searchProducts } = await import('@/api/productApi')
    vi.mocked(searchProducts).mockResolvedValueOnce({
      data: [],
      pagination: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
      traceId: '',
    })

    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/search?keyword=nonexistent')
    await router.isReady()

    const wrapper = mount(SearchResultsView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('No results found')
  })

  it('should show skeleton while loading', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/search?keyword=test')
    await router.isReady()

    const wrapper = mount(SearchResultsView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })

    const { useProductStore } = await import('@/stores/useProductStore')
    const store = useProductStore()
    store.isLoading = true
    store.searchResults = []
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('.product-card-skeleton').length).toBeGreaterThan(0)
  })
})
