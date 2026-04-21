import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import HomeView from '../HomeView.vue'

vi.mock('@/api/productApi', () => ({
  getProducts: vi.fn().mockResolvedValue({
    data: [
      {
        id: 1,
        sku: 'P1',
        name: 'Product 1',
        price: 10,
        rating: 4,
        brand: 'B',
        stockQuantity: 50,
        categoryName: 'C',
        primaryImageUrl: '/1.jpg',
      },
      {
        id: 2,
        sku: 'P2',
        name: 'Product 2',
        price: 20,
        rating: 3,
        brand: 'B',
        stockQuantity: 5,
        categoryName: 'C',
        primaryImageUrl: '/2.jpg',
      },
    ],
    pagination: { page: 0, size: 20, totalElements: 2, totalPages: 1 },
    traceId: '',
  }),
  searchProducts: vi.fn().mockResolvedValue({
    data: [],
    pagination: { page: 0, size: 5, totalElements: 0, totalPages: 0 },
    traceId: '',
  }),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: HomeView },
      { path: '/products/:id', component: { template: '<div />' } },
    ],
  })
}

describe('HomeView', () => {
  it('should render page title', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(HomeView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.home__title').text()).toBe('Discover Products')
  })

  it('should render product cards after loading', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(HomeView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    const cards = wrapper.findAll('.product-card')
    expect(cards.length).toBe(2)
  })

  it('should render product grid with correct class', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(HomeView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.home__grid').exists()).toBe(true)
  })

  it('should show skeleton while loading', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(HomeView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })

    // Manually set loading state to simulate in-flight request
    const { useProductStore } = await import('@/stores/useProductStore')
    const store = useProductStore()
    store.isLoading = true
    store.products = []
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('.product-card-skeleton').length).toBeGreaterThan(0)
  })
})
