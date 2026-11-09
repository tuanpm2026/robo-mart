import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { searchProducts } from '@/api/productApi'
import type { PagedResponse, ProductListItem } from '@/types/product'
import { useProductStore } from '../useProductStore'

vi.mock('@/api/productApi', () => ({
  getProducts: vi.fn(),
  getProduct: vi.fn(),
  searchProducts: vi.fn(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function result(id: number, page = 0): PagedResponse<ProductListItem> {
  return {
    data: [
      {
        id,
        sku: `P${id}`,
        name: `Product ${id}`,
        price: 10,
        rating: 4,
        brand: 'Fixture',
        stockQuantity: 10,
        categoryName: 'Fixture',
        primaryImageUrl: '/fixture.png',
      },
    ],
    pagination: { page, size: 20, totalElements: 60, totalPages: 3 },
    traceId: 'search-regression',
  }
}

describe('useProductStore search request lifecycle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('keeps the latest results and pagination when an older response arrives last', async () => {
    const older = deferred<PagedResponse<ProductListItem>>()
    const latest = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts).mockReturnValueOnce(older.promise).mockReturnValueOnce(latest.promise)
    const store = useProductStore()
    const first = store.fetchSearchResults({ keyword: 'older' })
    const second = store.fetchSearchResults({ keyword: 'latest' })

    latest.resolve(result(2))
    await second
    older.resolve(result(1, 2))
    await first

    expect(store.searchResults).toEqual(result(2).data)
    expect(store.searchPagination).toEqual(result(2).pagination)
    expect(store.filters).toEqual({ keyword: 'latest' })
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it.each(['success', 'failure'] as const)(
    'ignores stale %s while the latest request is loading',
    async (outcome) => {
      const older = deferred<PagedResponse<ProductListItem>>()
      const latest = deferred<PagedResponse<ProductListItem>>()
      vi.mocked(searchProducts)
        .mockReturnValueOnce(older.promise)
        .mockReturnValueOnce(latest.promise)
      const store = useProductStore()
      const first = store.fetchSearchResults({ keyword: 'older' })
      const second = store.fetchSearchResults({ keyword: 'latest' })

      if (outcome === 'success') older.resolve(result(1))
      else older.reject(new Error('Obsolete failure'))
      await first

      expect(store.isLoading).toBe(true)
      expect(store.error).toBeNull()
      expect(store.searchResults).toEqual([])
      expect(store.searchPagination).toBeNull()
      latest.resolve(result(2))
      await second
      expect(store.searchResults).toEqual(result(2).data)
    },
  )

  it('does not expose a late obsolete failure after the latest search succeeds', async () => {
    const older = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts).mockReturnValueOnce(older.promise).mockResolvedValueOnce(result(2))
    const store = useProductStore()
    const first = store.fetchSearchResults({ keyword: 'older' })
    await store.fetchSearchResults({ keyword: 'latest' })
    older.reject(new Error('Obsolete failure'))
    await first
    expect(store.error).toBeNull()
    expect(store.searchResults).toEqual(result(2).data)
  })

  it('retains the latest failure without reviving older data or pagination', async () => {
    const older = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts)
      .mockReturnValueOnce(older.promise)
      .mockRejectedValueOnce(new Error('Latest failure'))
    const store = useProductStore()
    const first = store.fetchSearchResults({ keyword: 'older' })
    await store.fetchSearchResults({ keyword: 'latest' })
    older.resolve(result(1))
    await first

    expect(store.error).toBe('Latest failure')
    expect(store.isLoading).toBe(false)
    expect(store.searchResults).toEqual([])
    expect(store.searchPagination).toBeNull()
    expect(store.hasMoreSearchResults).toBe(false)
  })

  it.each(['clearFilters', '$reset'] as const)(
    '%s invalidates a pending response and permits a new search',
    async (reset) => {
      const older = deferred<PagedResponse<ProductListItem>>()
      const latest = deferred<PagedResponse<ProductListItem>>()
      vi.mocked(searchProducts)
        .mockReturnValueOnce(older.promise)
        .mockReturnValueOnce(latest.promise)
      const store = useProductStore()
      const first = store.fetchSearchResults({ keyword: 'older' })
      store[reset]()
      expect(store.filters).toEqual({})
      expect(store.isLoading).toBe(false)
      const second = store.fetchSearchResults({ keyword: 'latest' })
      older.resolve(result(1))
      await first
      expect(store.searchResults).toEqual([])
      expect(store.searchPagination).toBeNull()
      expect(store.isLoading).toBe(true)
      latest.resolve(result(2))
      await second
      expect(store.searchResults).toEqual(result(2).data)
    },
  )

  it.each(['clearFilters', '$reset'] as const)(
    '%s ignores a late rejection and leaves search reset',
    async (reset) => {
      const older = deferred<PagedResponse<ProductListItem>>()
      vi.mocked(searchProducts).mockReturnValueOnce(older.promise)
      const store = useProductStore()
      const first = store.fetchSearchResults({ keyword: 'older' })
      store[reset]()
      older.reject(new Error('Obsolete failure'))
      await first
      expect(store.searchResults).toEqual([])
      expect(store.searchPagination).toBeNull()
      expect(store.error).toBeNull()
      expect(store.isLoading).toBe(false)
    },
  )

  it('appends the next page once using the current filter snapshot', async () => {
    const next = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts).mockResolvedValueOnce(result(1)).mockReturnValueOnce(next.promise)
    const store = useProductStore()
    await store.fetchSearchResults({ keyword: 'mouse', brand: 'Fixture' })
    const append = store.loadMoreSearchResults()
    await store.loadMoreSearchResults()
    expect(searchProducts).toHaveBeenCalledTimes(2)
    expect(searchProducts).toHaveBeenLastCalledWith({
      keyword: 'mouse',
      brand: 'Fixture',
      page: 1,
      size: 20,
    })
    expect(store.searchResults).toEqual(result(1).data)
    next.resolve(result(2, 1))
    await append
    expect(store.searchResults.map((product) => product.id)).toEqual([1, 2])
    expect(store.searchPagination?.page).toBe(1)
  })

  it('discards an old append after filters change and restarts pagination', async () => {
    const next = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts)
      .mockResolvedValueOnce(result(1))
      .mockReturnValueOnce(next.promise)
      .mockResolvedValueOnce(result(3))
    const store = useProductStore()
    await store.fetchSearchResults({ keyword: 'mouse', brand: 'Old' })
    const append = store.loadMoreSearchResults()
    await store.fetchSearchResults({ keyword: 'mouse', brand: 'New' })
    next.resolve(result(2, 1))
    await append
    expect(store.searchResults).toEqual(result(3).data)
    expect(store.searchPagination?.page).toBe(0)
    expect(store.filters.brand).toBe('New')
  })

  it('does not append previous-query pages while a replacement query is pending', async () => {
    const latest = deferred<PagedResponse<ProductListItem>>()
    vi.mocked(searchProducts).mockResolvedValueOnce(result(1)).mockReturnValueOnce(latest.promise)
    const store = useProductStore()
    await store.fetchSearchResults({ keyword: 'older' })
    const replacement = store.fetchSearchResults({ keyword: 'latest' })
    await store.loadMoreSearchResults()
    expect(searchProducts).toHaveBeenCalledTimes(2)
    expect(store.searchResults).toEqual([])
    expect(store.hasMoreSearchResults).toBe(false)
    latest.resolve(result(2))
    await replacement
  })

  it('copies the submitted filters rather than retaining a mutable caller snapshot', async () => {
    vi.mocked(searchProducts).mockResolvedValue(result(1))
    const store = useProductStore()
    const snapshot = { keyword: 'mouse', brand: 'Fixture' }
    await store.fetchSearchResults(snapshot)
    snapshot.brand = 'Changed externally'
    expect(store.filters.brand).toBe('Fixture')
  })

  it('retains the current page after an append failure and retries that same next page', async () => {
    vi.mocked(searchProducts)
      .mockResolvedValueOnce(result(1))
      .mockRejectedValueOnce(new Error('Next page failed'))
      .mockResolvedValueOnce(result(2, 1))
    const store = useProductStore()
    await store.fetchSearchResults({ keyword: 'mouse' })
    await store.loadMoreSearchResults()
    expect(store.searchResults).toEqual(result(1).data)
    expect(store.searchPagination?.page).toBe(0)
    expect(store.error).toBe('Next page failed')
    expect(store.isLoading).toBe(false)

    await store.loadMoreSearchResults()
    expect(searchProducts).toHaveBeenLastCalledWith({ keyword: 'mouse', page: 1, size: 20 })
    expect(store.searchResults.map((product) => product.id)).toEqual([1, 2])
    expect(store.error).toBeNull()
  })

  it.each(['clearFilters', '$reset'] as const)(
    '%s retires a pending append without allowing stale pages to be loaded again',
    async (reset) => {
      const next = deferred<PagedResponse<ProductListItem>>()
      vi.mocked(searchProducts).mockResolvedValueOnce(result(1)).mockReturnValueOnce(next.promise)
      const store = useProductStore()
      await store.fetchSearchResults({ keyword: 'mouse' })
      const append = store.loadMoreSearchResults()
      store[reset]()
      next.resolve(result(2, 1))
      await append
      await store.loadMoreSearchResults()
      expect(searchProducts).toHaveBeenCalledTimes(2)
      expect(store.searchResults).toEqual([])
      expect(store.searchPagination).toBeNull()
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    },
  )
})
