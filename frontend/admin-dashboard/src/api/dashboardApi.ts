import adminClient from './adminClient'

export interface OrderMetrics {
  ordersToday: number
  revenueToday: number
}

export interface InventoryMetrics {
  lowStockCount: number
}

export async function fetchOrderMetrics(): Promise<OrderMetrics> {
  const { data } = await adminClient.get<{ data: OrderMetrics; traceId: string }>(
    '/api/v1/admin/orders/metrics',
  )
  return data.data
}

export async function fetchInventoryMetrics(): Promise<InventoryMetrics> {
  const { data } = await adminClient.get<{ data: InventoryMetrics; traceId: string }>(
    '/api/v1/admin/inventory/metrics',
  )
  return data.data
}
