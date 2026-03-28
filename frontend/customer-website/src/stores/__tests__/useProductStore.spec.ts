import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProductStore } from '../useProductStore'

vi.mock('@/api/productApi', () => ({
  getProducts: vi.fn().mockResolvedValue({
    data: [
      { id: 1, sku: 'P1', name: 'Product 1', price: 10, rating: 4, brand: 'B', stockQuantity: 50, categoryName: 'C', primaryImageUrl: '/1.jpg' },
      { id: 2, sku: 'P2', name: 'Product 2', price: 20, rating: 3, brand: 'B', stockQuantity: 5, categoryName: 'C', primaryImageUrl: '/2.jpg' },
    ],
    pagination: { page: 0, size: 20, totalElements: 2, totalPages: 1 },
    traceId: 'trace-1',
  }),
  getProduct: vi.fn().mockResolvedValue({
    data: {
      id: 1, sku: 'P1', name: 'Product 1', description: 'Desc', price: 10, rating: 4, brand: 'B', stockQuantity: 50,
      category: { id: 1, name: 'Cat', description: 'Cat desc' },
      images: [{ id: 1, imageUrl: '/1.jpg', altText: 'Alt', displayOrder: 1 }],
      createdAt: '2026-01-01', updatedAt: '2026-01-01',
    },
    traceId: 'trace-2',
  }),
  searchProducts: vi.fn().mockResolvedValue({
    data: [
      { id: 1, sku: 'P1', name: 'Product 1', price: 10, rating: 4, brand: 'B', stockQuantity: 50, categoryName: 'C', primaryImageUrl: '/1.jpg' },
      { id: 2, sku: 'P2', name: 'Product 2', price: 20, rating: 3, brand: 'B', stockQuantity: 5, categoryName: 'C', primaryImageUrl: '/2.jpg' },
    ],
    pagination: { page: 0, size: 20, totalElements: 2, totalPages: 1 },
    traceId: 'trace-1',
  }),
}))

describe('useProductStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should start with empty state', () => {
    const store = useProductStore()
    expect(store.products).toEqual([])
    expect(store.searchResults).toEqual([])
    expect(store.selectedProduct).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('should fetch products', async () => {
    const store = useProductStore()
    await store.fetchProducts()
    expect(store.products).toHaveLength(2)
    expect(store.products[0]!.name).toBe('Product 1')
    expect(store.isLoading).toBe(false)
  })

  it('should fetch products with category filter', async () => {
    const store = useProductStore()
    const { getProducts } = await import('@/api/productApi')
    await store.fetchProducts(5)
    expect(getProducts).toHaveBeenCalledWith({ categoryId: 5, page: 0, size: 20 })
  })

  it('should search products', async () => {
    const store = useProductStore()
    await store.fetchSearchResults({ keyword: 'test' })
    expect(store.searchResults).toHaveLength(2)
  })

  it('should fetch product detail', async () => {
    const store = useProductStore()
    await store.fetchProductDetail(1)
    expect(store.selectedProduct).not.toBeNull()
    expect(store.selectedProduct!.name).toBe('Product 1')
  })

  it('should handle fetch error', async () => {
    const { getProducts } = await import('@/api/productApi')
    vi.mocked(getProducts).mockRejectedValueOnce(new Error('Network error'))

    const store = useProductStore()
    await store.fetchProducts()
    expect(store.error).toBe('Network error')
    expect(store.products).toEqual([])
  })

  it('should set isLoading during fetch', async () => {
    const store = useProductStore()
    const promise = store.fetchProducts()
    expect(store.isLoading).toBe(true)
    await promise
    expect(store.isLoading).toBe(false)
  })

  it('should clear filters', () => {
    const store = useProductStore()
    store.filters = { keyword: 'test', brand: 'Brand' }
    store.clearFilters()
    expect(store.filters).toEqual({})
  })

  it('should report hasMoreProducts correctly', async () => {
    const store = useProductStore()
    await store.fetchProducts()
    expect(store.hasMoreProducts).toBe(false)
  })

  it('should reset state', async () => {
    const store = useProductStore()
    await store.fetchProducts()
    store.$reset()
    expect(store.products).toEqual([])
    expect(store.pagination).toBeNull()
  })
})
