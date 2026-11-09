import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
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
  {
    id: 1,
    sku: 'SKU-001',
    name: 'Product A',
    description: null,
    price: 29.99,
    brand: 'BrandA',
    rating: 4.5,
    stockQuantity: 100,
    categoryId: 1,
    categoryName: 'Electronics',
    primaryImageUrl: null,
  },
  {
    id: 2,
    sku: 'SKU-002',
    name: 'Product B',
    description: null,
    price: 49.99,
    brand: 'BrandB',
    rating: 3.8,
    stockQuantity: 0,
    categoryId: 2,
    categoryName: 'Toys',
    primaryImageUrl: null,
  },
  {
    id: 3,
    sku: 'SKU-003',
    name: 'Product C',
    description: null,
    price: 9.99,
    brand: null,
    rating: null,
    stockQuantity: 50,
    categoryId: 1,
    categoryName: 'Electronics',
    primaryImageUrl: null,
  },
]

function createGlobalConfig() {
  const pinia = createPinia()
  return {
    plugins: [
      pinia,
      [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]],
      ToastService,
      ConfirmationService,
    ],
    stubs: {
      DataTable: {
        template:
          '<div data-testid="datatable"><slot name="loading" /><slot name="empty" /><slot /></div>',
      },
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

  it('renders and filters products with the real PrimeVue table', async () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
      traceId: 'test',
    })
    const config = createGlobalConfig()
    const wrapper = mount(ProductsPage, {
      global: {
        ...config,
        stubs: { ...config.stubs, DataTable: false, Column: false, Select: true },
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Product A')
    await wrapper.get('input[placeholder="Search name"]').setValue('Product B')
    await flushPromises()
    expect(wrapper.text()).toContain('Product B')
    expect(wrapper.text()).not.toContain('Product A')
    wrapper.unmount()
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
  it('names actual row actions and focuses inline price editing then returns on Escape', async () => {
    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
      traceId: 'test',
    })
    const config = createGlobalConfig()
    const wrapper = mount(ProductsPage, {
      attachTo: document.body,
      global: {
        ...config,
        stubs: {
          ...config.stubs,
          DataTable: false,
          Column: false,
          Button: false,
          InputNumber: false,
          Tag: false,
          Select: true,
        },
      },
    })
    await flushPromises()
    expect(wrapper.find('button[aria-label="Edit Product A"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="Delete Product A"]').exists()).toBe(true)
    const action = wrapper.get('button[aria-label="Edit price for Product A"]')
    await action.trigger('click')
    await flushPromises()
    const input = wrapper.get('input[aria-label="Price for Product A"]')
    expect(document.activeElement).toBe(input.element)
    await input.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('#edit-price-1').element)
    wrapper.unmount()
  })

  it('announces product loading failure and retries through a real button', async () => {
    vi.mocked(listProducts)
      .mockRejectedValueOnce(new Error('fixture unavailable'))
      .mockResolvedValueOnce({
        data: mockProducts,
        pagination: { page: 0, size: 100, totalElements: 3, totalPages: 1 },
        traceId: 'test',
      })
    const config = createGlobalConfig()
    const wrapper = mount(ProductsPage, {
      global: { ...config, stubs: { ...config.stubs, Button: false } },
    })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Could not load products')
    await wrapper.get('button[aria-label="Retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(listProducts).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
