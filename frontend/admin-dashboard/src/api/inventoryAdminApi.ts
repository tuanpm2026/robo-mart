import adminClient from './adminClient'

export interface InventoryItem {
  id: number
  productId: number
  availableQuantity: number
  reservedQuantity: number
  totalQuantity: number
  lowStockThreshold: number
  updatedAt: string
}

export interface InventoryItemEnriched extends InventoryItem {
  productName: string
  sku: string
}

interface PagedInventoryResponse {
  data: InventoryItem[]
  pagination: { page: number; size: number; totalElements: number; totalPages: number }
  traceId: string
}

interface ApiResponse<T> {
  data: T
  traceId: string
}

export async function listInventory(page = 0, size = 25): Promise<PagedInventoryResponse> {
  const { data } = await adminClient.get<PagedInventoryResponse>(
    `/api/v1/admin/inventory?page=${page}&size=${size}`,
  )
  return data
}

export async function restockItem(
  productId: number,
  quantity: number,
  reason?: string,
): Promise<InventoryItem> {
  const { data } = await adminClient.put<ApiResponse<InventoryItem>>(
    `/api/v1/admin/inventory/${productId}/restock`,
    { quantity, reason },
  )
  return data.data
}

export async function bulkRestock(
  productIds: number[],
  quantity: number,
  reason?: string,
): Promise<InventoryItem[]> {
  const { data } = await adminClient.post<InventoryItem[]>(`/api/v1/admin/inventory/bulk-restock`, {
    productIds,
    quantity,
    reason,
  })
  return data
}
