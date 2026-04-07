import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { adminTheme } from '@robo-mart/shared'

// Mock the API module
vi.mock('@/api/productAdminApi', () => ({
  listProducts: vi.fn(),
  deleteProduct: vi.fn(),
  updateProduct: vi.fn(),
}))

import { listProducts } from '@/api/productAdminApi'
import ProductsPage from '../views/ProductsPage.vue'

const mockProducts = [
  { id: 1, sku: 'SKU-001', name: 'Product A', price: 29.99, brand: 'BrandA', rating: 4.5, stockQuantity: 100, categoryId: 1, categoryName: 'Electronics', primaryImageUrl: null },
  { id: 2, sku: 'SKU-002', name: 'Product B', price: 49.99, brand: 'BrandB', rating: 3.8, stockQuantity: 0, categoryId: 2, categoryName: 'Toys', primaryImageUrl: null },
  { id: 3, sku: 'SKU-003', name: 'Product C', price: 9.99, brand: null, rating: null, stockQuantity: 50, categoryId: 1, categoryName: 'Electronics', primaryImageUrl: null },
]

function createGlobalConfig() {
  const pinia = createPinia()
  return {
    plugins: [pinia, [PrimeVue, { theme: { preset: adminTheme } }], ToastService, ConfirmationService],
    stubs: {
      DataTable: { template: '<div data-testid="datatable"><slot name="loading" /><slot name="empty" /><slot /></div>' },
      Column: { template: '<div><slot name="body" :data="{}" /></div>' },
      Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      ProductFormSlideOver: { template: '<div />' },
      InputNumber: { template: '<input />' },
      Tag: { template: '<span><slot /></span>' },
      Skeleton: { template: '<div />' },
      EmptyState: { template: '<div data-testid="empty-state" />' },
    },
  }
}

describe('ProductsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders DataTable after products are loaded', async () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
      traceId: 'test',
    })

    const wrapper = shallowMount(ProductsPage, { global: createGlobalConfig() })

    // Wait for onMounted to complete
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(listProducts).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-testid="datatable"]').exists()).toBe(true)
  })

  it('calls listProducts on mount', () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: [],
      pagination: { page: 0, size: 100, totalElements: 0, totalPages: 0 },
      traceId: 'test',
    })

    shallowMount(ProductsPage, { global: createGlobalConfig() })
    expect(listProducts).toHaveBeenCalledWith(0, 100)
  })

  it('sets showForm to true when Add Product button triggers openCreate', async () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
      traceId: 'test',
    })

    const wrapper = shallowMount(ProductsPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    // Access the component's internal state via vm
    const vm = wrapper.vm as any
    expect(vm.showForm).toBe(false)
    vm.openCreate()
    expect(vm.showForm).toBe(true)
    expect(vm.editingProduct).toBeNull()
  })

  it('sets editingProduct when openEdit is called', async () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
      traceId: 'test',
    })

    const wrapper = shallowMount(ProductsPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    vm.openEdit(mockProducts[0])
    expect(vm.showForm).toBe(true)
    expect(vm.editingProduct).toEqual(mockProducts[0])
  })

  it('shows loading state initially', () => {
    vi.mocked(listProducts).mockReturnValue(new Promise(() => {}) as any)

    const wrapper = shallowMount(ProductsPage, { global: createGlobalConfig() })
    const vm = wrapper.vm as any
    expect(vm.isLoading).toBe(true)
  })
})
