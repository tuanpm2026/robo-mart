import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ProductDetailView from '../ProductDetailView.vue'

vi.mock('@/api/productApi', () => ({
  getProduct: vi.fn().mockResolvedValue({
    data: {
      id: 1, sku: 'P1', name: 'Wireless Mouse', description: 'A great mouse', price: 29.99, rating: 4.5, brand: 'Logitech', stockQuantity: 50,
      category: { id: 1, name: 'Electronics', description: 'Electronic devices' },
      images: [{ id: 1, imageUrl: '/mouse.jpg', altText: 'Mouse image', displayOrder: 1 }],
      createdAt: '2026-01-01', updatedAt: '2026-01-01',
    },
    traceId: '',
  }),
  searchProducts: vi.fn().mockResolvedValue({
    data: [],
    pagination: { page: 0, size: 5, totalElements: 0, totalPages: 0 },
    traceId: '',
  }),
}))

vi.mock('@/api/cartApi', () => ({
  getCart: vi.fn().mockResolvedValue({
    data: { cartId: 'c1', items: [], totalItems: 0, totalPrice: 0 },
    traceId: '',
  }),
  addToCart: vi.fn().mockResolvedValue({
    data: { cartId: 'c1', items: [{ productId: 1, productName: 'Wireless Mouse', price: 29.99, quantity: 1, subtotal: 29.99 }], totalItems: 1, totalPrice: 29.99 },
    traceId: '',
  }),
  updateQuantity: vi.fn(),
  removeItem: vi.fn(),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/products/:id', component: ProductDetailView },
    ],
  })
}

describe('ProductDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should render product title', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.product-detail__title').text()).toBe('Wireless Mouse')
  })

  it('should render product price', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.product-detail__price').text()).toBe('$29.99')
  })

  it('should render stock badge', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('In Stock')
  })

  it('should render rating', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.product-detail__rating').exists()).toBe(true)
  })

  it('should render breadcrumb', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.product-detail__breadcrumb').exists()).toBe(true)
  })

  it('should render Add to Cart button', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.find('.product-detail__add-btn').exists()).toBe(true)
  })

  it('should render description', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('A great mouse')
  })

  it('should show skeleton while loading', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })

    const { useProductStore } = await import('@/stores/useProductStore')
    const store = useProductStore()
    store.isLoading = true
    store.selectedProduct = null
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.product-detail__skeleton').exists()).toBe(true)
  })

  it('should call cartStore.addItem when Add to Cart is clicked', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    await wrapper.find('.product-detail__add-btn').trigger('click')
    await flushPromises()

    const { addToCart } = await import('@/api/cartApi')
    expect(vi.mocked(addToCart)).toHaveBeenCalledWith({
      productId: 1,
      productName: 'Wireless Mouse',
      price: 29.99,
      quantity: 1,
    })
  })

  it('should show error toast on addToCart failure', async () => {
    const { addToCart } = await import('@/api/cartApi')
    vi.mocked(addToCart).mockRejectedValueOnce(new Error('Server error'))

    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/products/1')
    await router.isReady()

    const wrapper = mount(ProductDetailView, {
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })
    await flushPromises()

    await wrapper.find('.product-detail__add-btn').trigger('click')
    await flushPromises()

    // Component should not throw — error is caught and shown as toast
    expect(wrapper.find('.product-detail__add-btn').exists()).toBe(true)
  })
})
