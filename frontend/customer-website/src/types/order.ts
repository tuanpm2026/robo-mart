export type OrderStatus =
  | 'PENDING'
  | 'PAYMENT_PENDING'
  | 'INVENTORY_RESERVING'
  | 'PAYMENT_PROCESSING'
  | 'CONFIRMED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'PAYMENT_REFUNDING'
  | 'INVENTORY_RELEASING'
  | 'CANCELLED'

export interface OrderStatusHistoryEntry {
  status: OrderStatus
  changedAt: string
}

export interface OrderItem {
  productId: number
  productName: string
  quantity: number
  unitPrice: number
  subtotal: number
}

export interface OrderSummary {
  id: number
  createdAt: string
  totalAmount: number
  status: OrderStatus
  itemCount: number
  cancellationReason: string | null
}

export interface OrderDetail {
  id: number
  createdAt: string
  updatedAt: string
  totalAmount: number
  status: OrderStatus
  shippingAddress: string | null
  cancellationReason: string | null
  items: OrderItem[]
  statusHistory: OrderStatusHistoryEntry[]
}

export interface OrderListParams {
  page?: number
  size?: number
}

export interface CreateOrderItemPayload {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
}

export interface PlaceOrderRequest {
  items: CreateOrderItemPayload[]
  shippingAddress: string
}
