import type { CartItem, CheckoutCartItem } from '@/types/cart'
import type { ShippingFormData } from '@/types/checkout'
import type { PlaceOrderRequest } from '@/types/order'

export interface CheckoutAttempt {
  key: string
  request: PlaceOrderRequest
  shipping: ShippingFormData
  items: (CartItem & CheckoutCartItem)[]
  orderId?: number
}

const storageKey = (userId: string) => `robomart-checkout:${userId}`

export function readAttempt(userId: string): CheckoutAttempt | null {
  const value = sessionStorage.getItem(storageKey(userId))
  if (!value) return null
  const attempt = JSON.parse(value) as CheckoutAttempt
  if (
    !attempt.key ||
    !attempt.request?.shippingAddress ||
    !attempt.shipping ||
    !Array.isArray(attempt.items) ||
    !attempt.items.length ||
    !attempt.items.every((item) => item.lineId && item.productId > 0 && item.quantity > 0)
  ) {
    throw new Error('The saved checkout could not be restored. Please review your orders.')
  }
  return attempt
}

export function saveAttempt(userId: string, attempt: CheckoutAttempt): void {
  // Persist before sending. Card details are deliberately absent from this record.
  sessionStorage.setItem(storageKey(userId), JSON.stringify(attempt))
}

export function clearAttempt(userId: string): void {
  sessionStorage.removeItem(storageKey(userId))
}
