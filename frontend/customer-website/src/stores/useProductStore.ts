import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getProducts, getProduct, searchProducts } from '@/api/productApi'
import type {
  ProductListItem,
  ProductDetail,
  ProductSearchParams,
  PaginationMeta,
} from '@/types/product'

export const useProductStore = defineStore('product', () => {
  const products = ref<ProductListItem[]>([])
  const searchResults = ref<ProductListItem[]>([])
  const selectedProduct = ref<ProductDetail | null>(null)
  const pagination = ref<PaginationMeta | null>(null)
  const searchPagination = ref<PaginationMeta | null>(null)
  const filters = ref<ProductSearchParams>({})
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  let searchRequestId = 0

  const hasMoreProducts = computed(
    () => pagination.value !== null && pagination.value.page < pagination.value.totalPages - 1,
  )

  const hasMoreSearchResults = computed(
    () =>
      searchPagination.value !== null &&
      searchPagination.value.page < searchPagination.value.totalPages - 1,
  )

  async function fetchProducts(categoryId?: number, page = 0) {
    isLoading.value = true
    error.value = null
    try {
      const response = await getProducts({ categoryId, page, size: 20 })
      if (page === 0) {
        products.value = response.data
      } else {
        products.value = [...products.value, ...response.data]
      }
      pagination.value = response.pagination
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load products'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchSearchResults(params: ProductSearchParams, page = 0) {
    const requestId = ++searchRequestId
    const snapshot = { ...params }
    filters.value = snapshot
    if (page === 0) {
      searchResults.value = []
      searchPagination.value = null
    }
    isLoading.value = true
    error.value = null
    try {
      const response = await searchProducts({ ...snapshot, page, size: 20 })
      if (requestId !== searchRequestId) return
      if (page === 0) {
        searchResults.value = response.data
      } else {
        searchResults.value = [...searchResults.value, ...response.data]
      }
      searchPagination.value = response.pagination
    } catch (err) {
      if (requestId === searchRequestId) {
        error.value = err instanceof Error ? err.message : 'Failed to search products'
      }
    } finally {
      if (requestId === searchRequestId) isLoading.value = false
    }
  }

  async function fetchProductDetail(id: number) {
    isLoading.value = true
    error.value = null
    selectedProduct.value = null
    try {
      const response = await getProduct(id)
      selectedProduct.value = response.data
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load product'
    } finally {
      isLoading.value = false
    }
  }

  async function loadMoreProducts(categoryId?: number) {
    if (!hasMoreProducts.value || !pagination.value) return
    await fetchProducts(categoryId, pagination.value.page + 1)
  }

  async function loadMoreSearchResults() {
    if (isLoading.value || !hasMoreSearchResults.value || !searchPagination.value) return
    await fetchSearchResults(filters.value, searchPagination.value.page + 1)
  }

  function clearFilters() {
    // Clearing/resetting a search retires every pending response, including appended pages.
    searchRequestId++
    filters.value = {}
    searchResults.value = []
    searchPagination.value = null
    isLoading.value = false
    error.value = null
  }

  function $reset() {
    searchRequestId++
    products.value = []
    searchResults.value = []
    selectedProduct.value = null
    pagination.value = null
    searchPagination.value = null
    filters.value = {}
    isLoading.value = false
    error.value = null
  }

  return {
    products,
    searchResults,
    selectedProduct,
    pagination,
    searchPagination,
    filters,
    isLoading,
    error,
    hasMoreProducts,
    hasMoreSearchResults,
    fetchProducts,
    fetchSearchResults,
    fetchProductDetail,
    loadMoreProducts,
    loadMoreSearchResults,
    clearFilters,
    $reset,
  }
})
