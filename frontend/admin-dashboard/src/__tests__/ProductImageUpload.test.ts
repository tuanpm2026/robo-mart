import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'

// Mock the API module
vi.mock('@/api/productAdminApi', () => ({
  uploadImages: vi.fn(),
  deleteImage: vi.fn(),
  reorderImages: vi.fn(),
}))

import { uploadImages, deleteImage, reorderImages } from '@/api/productAdminApi'
import ProductImageUpload from '../components/products/ProductImageUpload.vue'
import type { ProductImage } from '@/api/productAdminApi'

const mockImages: ProductImage[] = [
  { id: 1, imageUrl: 'http://localhost:8081/images/1/abc.jpg', altText: null, displayOrder: 0 },
  { id: 2, imageUrl: 'http://localhost:8081/images/1/def.jpg', altText: null, displayOrder: 1 },
]

function createGlobalConfig() {
  const pinia = createPinia()
  return {
    plugins: [pinia, [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [typeof PrimeVue, ...unknown[]], ToastService],
    stubs: {
      FileUpload: {
        template: '<div data-testid="file-upload"><slot name="empty" /></div>',
        props: ['multiple', 'accept', 'maxFileSize', 'customUpload'],
        emits: ['select'],
      },
      ProgressBar: { template: '<div />' },
      Button: {
        template: '<button @click="$emit(\'click\')"><slot /></button>',
        emits: ['click'],
      },
      Badge: { template: '<span><slot /></span>' },
    },
  }
}

describe('ProductImageUpload', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders existing images as thumbnails when existingImages prop provided', () => {
    const wrapper = shallowMount(ProductImageUpload, {
      props: { productId: 1, existingImages: mockImages },
      global: createGlobalConfig(),
    })

    const imgs = wrapper.findAll('img.image-thumb')
    expect(imgs).toHaveLength(2)
    expect(imgs[0]!.attributes('src')).toBe('http://localhost:8081/images/1/abc.jpg')
    expect(imgs[1]!.attributes('src')).toBe('http://localhost:8081/images/1/def.jpg')
  })

  it('shows "Primary" badge on first image (displayOrder = 0)', () => {
    const wrapper = shallowMount(ProductImageUpload, {
      props: { productId: 1, existingImages: mockImages },
      global: createGlobalConfig(),
    })

    const primaryBadges = wrapper.findAll('.primary-badge')
    expect(primaryBadges).toHaveLength(1)
    // Only the first card should have the primary badge
    const firstCard = wrapper.findAll('.image-card')[0]!
    expect(firstCard.find('.primary-badge').exists()).toBe(true)
  })

  it('delete button click calls deleteImage() and emits update:existingImages', async () => {
    vi.mocked(deleteImage).mockResolvedValue(undefined)

    const wrapper = shallowMount(ProductImageUpload, {
      props: { productId: 1, existingImages: mockImages },
      global: createGlobalConfig(),
    })

    // Trigger removeImage for first image
    const vm = wrapper.vm as any
    await vm.removeImage(mockImages[0])

    expect(deleteImage).toHaveBeenCalledWith(1, 1)
    const emitted = wrapper.emitted('update:existingImages')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual([mockImages[1]]) // only second image remains
  })

  it('file selection in create mode (productId=null) emits pendingFiles without calling API', async () => {
    const wrapper = shallowMount(ProductImageUpload, {
      props: { productId: null, existingImages: [] },
      global: createGlobalConfig(),
    })

    const files = [new File(['data'], 'img.jpg', { type: 'image/jpeg' })]
    const vm = wrapper.vm as any
    await vm.onFilesSelected({ files })

    expect(uploadImages).not.toHaveBeenCalled()
    const emitted = wrapper.emitted('pendingFiles')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual(files)
  })

  it('file selection in edit mode (productId=1) calls uploadImages() immediately', async () => {
    const newImage: ProductImage = { id: 3, imageUrl: 'http://localhost:8081/images/1/new.jpg', altText: null, displayOrder: 0 }
    vi.mocked(uploadImages).mockResolvedValue([newImage])

    const wrapper = shallowMount(ProductImageUpload, {
      props: { productId: 1, existingImages: [] },
      global: createGlobalConfig(),
    })

    const files = [new File(['data'], 'img.jpg', { type: 'image/jpeg' })]
    const vm = wrapper.vm as any
    await vm.onFilesSelected({ files })

    expect(uploadImages).toHaveBeenCalledWith(1, files)
    const emitted = wrapper.emitted('update:existingImages')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual([newImage])
  })
})
