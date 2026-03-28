import apiClient from './client'
import type {
  ApiResponse,
  PagedResponse,
  ProductDetail,
  ProductListItem,
  ProductListParams,
  ProductSearchParams,
} from '@/types/product'

export async function getProducts(
  params: ProductListParams = {},
): Promise<PagedResponse<ProductListItem>> {
  const { data } = await apiClient.get<PagedResponse<ProductListItem>>('/api/v1/products', {
    params,
  })
  return data
}

export async function getProduct(id: number): Promise<ApiResponse<ProductDetail>> {
  const { data } = await apiClient.get<ApiResponse<ProductDetail>>(`/api/v1/products/${id}`)
  return data
}

export async function searchProducts(
  params: ProductSearchParams = {},
): Promise<PagedResponse<ProductListItem>> {
  const { data } = await apiClient.get<PagedResponse<ProductListItem>>('/api/v1/products/search', {
    params,
  })
  return data
}
