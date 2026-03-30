import apiClient from './client'
import type { ApiResponse } from '@/types/product'
import type { Cart, AddToCartRequest, UpdateQuantityRequest } from '@/types/cart'

export async function getCart(): Promise<ApiResponse<Cart>> {
  const { data } = await apiClient.get<ApiResponse<Cart>>('/api/v1/cart')
  return data
}

export async function addToCart(request: AddToCartRequest): Promise<ApiResponse<Cart>> {
  const { data } = await apiClient.post<ApiResponse<Cart>>('/api/v1/cart/items', request)
  return data
}

export async function updateQuantity(
  productId: number,
  request: UpdateQuantityRequest,
): Promise<ApiResponse<Cart>> {
  const { data } = await apiClient.put<ApiResponse<Cart>>(
    `/api/v1/cart/items/${productId}`,
    request,
  )
  return data
}

export async function removeItem(productId: number): Promise<void> {
  await apiClient.delete(`/api/v1/cart/items/${productId}`)
}
