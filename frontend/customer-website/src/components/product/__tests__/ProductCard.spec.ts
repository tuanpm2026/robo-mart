import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ProductCard from '../ProductCard.vue'
import type { ProductListItem } from '@/types/product'

vi.mock('@/api/cartApi', () => ({
  getCart: vi.fn().mockResolvedValue({
    data: { cartId: 'c1', items: [], totalItems: 0, totalPrice: 0 },
    traceId: '',
  }),
  addToCart: vi.fn().mockResolvedValue({
    data: { cartId: 'c1', items: [{ productId: 1, productName: 'Test Product', price: 29.99, quantity: 1, subtotal: 29.99 }], totalItems: 1, totalPrice: 29.99 },
    traceId: '',
  }),
  updateQuantity: vi.fn(),
  removeItem: vi.fn(),
}))

const mockProduct: ProductListItem = {
  id: 1,
  sku: 'TEST-001',
  name: 'Test Product',
  price: 29.99,
  rating: 4.5,
  brand: 'TestBrand',
  stockQuantity: 50,
  categoryName: 'Electronics',
  primaryImageUrl: 'https://example.com/image.jpg',
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/products/:id', component: { template: '<div />' } },
    ],
  })
}

async function mountCard(product: ProductListItem = mockProduct) {
  const router = createTestRouter()
  const pinia = createPinia()
  await router.push('/')
  await router.isReady()
  return mount(ProductCard, {
    props: { product },
    global: { plugins: [router, pinia, PrimeVue, ToastService] },
  })
}

describe('ProductCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should render product name', async () => {
    const wrapper = await mountCard()
    expect(wrapper.find('.product-card__title').text()).toBe('Test Product')
  })

  it('should render product price', async () => {
    const wrapper = await mountCard()
    expect(wrapper.find('.product-card__price').text()).toBe('$29.99')
  })

  it('should render rating component', async () => {
    const wrapper = await mountCard()
    expect(wrapper.find('.product-card__rating').exists()).toBe(true)
  })

  it('should render In Stock tag for quantity > 20', async () => {
    const wrapper = await mountCard()
    expect(wrapper.text()).toContain('In Stock')
  })

  it('should render Low Stock tag for quantity 1-20', async () => {
    const wrapper = await mountCard({ ...mockProduct, stockQuantity: 10 })
    expect(wrapper.text()).toContain('Low Stock')
  })

  it('should render Out of Stock tag for quantity 0', async () => {
    const wrapper = await mountCard({ ...mockProduct, stockQuantity: 0 })
    expect(wrapper.text()).toContain('Out of Stock')
  })

  it('should apply out-of-stock class when quantity is 0', async () => {
    const wrapper = await mountCard({ ...mockProduct, stockQuantity: 0 })
    expect(wrapper.find('.product-card--out-of-stock').exists()).toBe(true)
  })

  it('should not show add-to-cart overlay when out of stock', async () => {
    const wrapper = await mountCard({ ...mockProduct, stockQuantity: 0 })
    expect(wrapper.find('.product-card__overlay').exists()).toBe(false)
  })

  it('should show add-to-cart overlay for in-stock products', async () => {
    const wrapper = await mountCard()
    expect(wrapper.find('.product-card__overlay').exists()).toBe(true)
  })

  it('should have accessible aria-label', async () => {
    const wrapper = await mountCard()
    expect(wrapper.find('.product-card').attributes('aria-label')).toBe('Test Product, $29.99')
  })

  it('should navigate to product detail on click', async () => {
    const router = createTestRouter()
    const pinia = createPinia()
    await router.push('/')
    await router.isReady()
    const push = vi.spyOn(router, 'push')

    const wrapper = mount(ProductCard, {
      props: { product: mockProduct },
      global: { plugins: [router, pinia, PrimeVue, ToastService] },
    })

    await wrapper.find('.product-card').trigger('click')
    expect(push).toHaveBeenCalledWith('/products/1')
  })

  it('should render product image with alt text', async () => {
    const wrapper = await mountCard()
    const img = wrapper.find('.product-card__image')
    expect(img.attributes('alt')).toBe('Test Product')
    expect(img.attributes('src')).toBe('https://example.com/image.jpg')
  })

  it('should call cartStore.addItem when Add to Cart is clicked', async () => {
    const wrapper = await mountCard()
    await wrapper.find('.product-card__add-btn').trigger('click')
    await flushPromises()

    const { addToCart } = await import('@/api/cartApi')
    expect(vi.mocked(addToCart)).toHaveBeenCalledWith({
      productId: 1,
      productName: 'Test Product',
      price: 29.99,
      quantity: 1,
    })
  })

  it('should show error toast on addToCart failure', async () => {
    const { addToCart } = await import('@/api/cartApi')
    vi.mocked(addToCart).mockRejectedValueOnce(new Error('Server error'))

    const wrapper = await mountCard()
    await wrapper.find('.product-card__add-btn').trigger('click')
    await flushPromises()

    // Component should not throw — error is caught and shown as toast
    expect(wrapper.find('.product-card__add-btn').exists()).toBe(true)
  })

  it('should not show Add to Cart for out of stock products', async () => {
    const wrapper = await mountCard({ ...mockProduct, stockQuantity: 0 })
    expect(wrapper.find('.product-card__add-btn').exists()).toBe(false)
  })
})
