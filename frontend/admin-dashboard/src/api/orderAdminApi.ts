import adminClient from './adminClient'

export interface AdminOrderSummary {
  id: number
  userId: string
  createdAt: string
  totalAmount: number
  status: string
  itemCount: number
  cancellationReason: string | null
}

export interface OrderItem {
  productId: number
  productName: string
  quantity: number
  unitPrice: number
  subtotal: number
}

export interface OrderStatusHistory {
  status: string
  changedAt: string
}

export interface AdminOrderDetail {
  id: number
  userId: string
  createdAt: string
  updatedAt: string
  totalAmount: number
  status: string
  shippingAddress: string | null
  cancellationReason: string | null
  items: OrderItem[]
  statusHistory: OrderStatusHistory[]
}

interface PagedResponse<T> {
  data: T[]
  pagination: { page: number; size: number; totalElements: number; totalPages: number }
  traceId: string
}

interface ApiResponse<T> {
  data: T
  traceId: string
}

export async function listOrders(
  page = 0,
  size = 25,
  statuses?: string[],
): Promise<PagedResponse<AdminOrderSummary>> {
  const params = new URLSearchParams()
  params.append('page', String(page))
  params.append('size', String(size))
  if (statuses && statuses.length > 0) {
    statuses.forEach((s) => params.append('statuses', s))
  }
  const { data } = await adminClient.get<PagedResponse<AdminOrderSummary>>(
    '/api/v1/admin/orders',
    { params },
  )
  return data
}

export async function getOrderDetail(orderId: number): Promise<AdminOrderDetail> {
  const { data } = await adminClient.get<ApiResponse<AdminOrderDetail>>(
    `/api/v1/admin/orders/${orderId}`,
  )
  return data.data
}

export async function updateOrderStatus(
  orderId: number,
  status: string,
): Promise<AdminOrderSummary> {
  const { data } = await adminClient.put<ApiResponse<AdminOrderSummary>>(
    `/api/v1/admin/orders/${orderId}/status`,
    { status },
  )
  return data.data
}
