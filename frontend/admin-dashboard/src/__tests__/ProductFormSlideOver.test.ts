import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'

// Mock the API module
vi.mock('@/api/productAdminApi', () => ({
  getCategories: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  getProductDetail: vi.fn(),
  uploadImages: vi.fn(),
}))

import { getCategories, createProduct, updateProduct, uploadImages } from '@/api/productAdminApi'
import ProductFormSlideOver from '../components/products/ProductFormSlideOver.vue'

const mockCategories = [
  { id: 1, name: 'Electronics', description: null },
  { id: 2, name: 'Toys', description: null },
]

const mockProduct = {
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
}

function createGlobalConfig() {
  const pinia = createPinia()
  return {
    plugins: [pinia, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
    stubs: {
      SlideOverPanel: {
        template: '<div data-testid="slide-over-panel"><slot /></div>',
        props: ['visible', 'title'],
      },
      InputText: { template: '<input />', props: ['modelValue'] },
      Textarea: { template: '<textarea />', props: ['modelValue'] },
      Select: { template: '<div />', props: ['modelValue', 'options'] },
      InputNumber: { template: '<input />', props: ['modelValue'] },
      Button: { template: '<button type="submit" @click="$emit(\'click\')"><slot /></button>' },
      ProductImageUpload: {
        template: '<div data-testid="product-image-upload" />',
        props: ['productId', 'existingImages'],
        emits: ['update:existingImages', 'pendingFiles'],
      },
    },
  }
}

describe('ProductFormSlideOver', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getCategories).mockResolvedValue(mockCategories)
  })

  it('renders with empty fields in create mode (product=null)', async () => {
    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: false, product: null },
      global: createGlobalConfig(),
    })
    // Trigger the watch by changing visible false → true
    await wrapper.setProps({ visible: true })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    expect(vm.name).toBe('')
    expect(vm.price).toBeNull()
    expect(vm.selectedCategoryId).toBeNull()
    expect(vm.brand).toBe('')
    expect(vm.sku).toBe('')
    expect(getCategories).toHaveBeenCalledOnce()
  })

  it('pre-populates fields in edit mode when product prop is provided', async () => {
    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: false, product: mockProduct },
      global: createGlobalConfig(),
    })
    await wrapper.setProps({ visible: true })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    expect(vm.name).toBe('Product A')
    expect(vm.price).toBe(29.99)
    expect(vm.selectedCategoryId).toBe(1)
    expect(vm.brand).toBe('BrandA')
  })

  it('shows name validation error when submitted with empty name', async () => {
    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: true, product: null },
      global: createGlobalConfig(),
    })

    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    // name is empty by default
    vm.validateField('name')
    await wrapper.vm.$nextTick()

    expect(vm.errors.name).toBe('Name is required')
  })

  it('calls createProduct when submitting in create mode', async () => {
    vi.mocked(createProduct).mockResolvedValue({
      id: 99,
      sku: 'SKU-AUTO',
      name: 'New Product',
      description: null,
      price: 15.0,
      brand: null,
      rating: null,
      stockQuantity: 0,
      category: { id: 1, name: 'Electronics', description: null },
      images: [],
      createdAt: '',
      updatedAt: '',
    })

    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: false, product: null },
      global: createGlobalConfig(),
    })
    await wrapper.setProps({ visible: true })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    vm.name = 'New Product'
    vm.selectedCategoryId = 1
    vm.price = 15.0

    await vm.handleSubmit()

    expect(createProduct).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'New Product', categoryId: 1, price: 15.0 }),
    )
  })

  it('pending images are uploaded after create', async () => {
    vi.mocked(createProduct).mockResolvedValue({
      id: 42,
      sku: 'SKU-NEW',
      name: 'New Product',
      description: null,
      price: 10.0,
      brand: null,
      rating: null,
      stockQuantity: 0,
      category: { id: 1, name: 'Electronics', description: null },
      images: [],
      createdAt: '',
      updatedAt: '',
    })
    vi.mocked(uploadImages).mockResolvedValue([])

    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: false, product: null },
      global: createGlobalConfig(),
    })
    await wrapper.setProps({ visible: true })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    vm.name = 'New Product'
    vm.selectedCategoryId = 1
    vm.price = 10.0

    // Simulate pending files set by ProductImageUpload
    const pendingFile = new File(['data'], 'img.jpg', { type: 'image/jpeg' })
    vm.pendingFiles = [pendingFile]

    await vm.handleSubmit()

    expect(createProduct).toHaveBeenCalledOnce()
    expect(uploadImages).toHaveBeenCalledWith(42, [pendingFile])
  })

  it('calls updateProduct when submitting in edit mode', async () => {
    vi.mocked(updateProduct).mockResolvedValue({
      id: 1,
      sku: 'SKU-001',
      name: 'Product A Updated',
      description: null,
      price: 35.0,
      brand: 'BrandA',
      rating: null,
      stockQuantity: 100,
      category: { id: 1, name: 'Electronics', description: null },
      images: [],
      createdAt: '',
      updatedAt: '',
    })

    const wrapper = shallowMount(ProductFormSlideOver, {
      props: { visible: false, product: mockProduct },
      global: createGlobalConfig(),
    })
    await wrapper.setProps({ visible: true })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    vm.name = 'Product A Updated'
    vm.price = 35.0

    await vm.handleSubmit()

    expect(updateProduct).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ name: 'Product A Updated', price: 35.0 }),
    )
  })
})
