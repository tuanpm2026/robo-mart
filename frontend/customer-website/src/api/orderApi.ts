import apiClient from './client'
import type { PagedResponse, ApiResponse } from '@/types/product'
import type { OrderSummary, OrderDetail, OrderListParams } from '@/types/order'

export async function getOrders(params?: OrderListParams): Promise<PagedResponse<OrderSummary>> {
  const { data } = await apiClient.get<PagedResponse<OrderSummary>>('/api/v1/orders', { params })
  return data
}

export async function getOrder(orderId: number): Promise<ApiResponse<OrderDetail>> {
  const { data } = await apiClient.get<ApiResponse<OrderDetail>>(`/api/v1/orders/${orderId}`)
  return data
}

export async function cancelOrder(orderId: number, reason?: string): Promise<void> {
  await apiClient.post(`/api/v1/orders/${orderId}/cancel`, { reason })
}
