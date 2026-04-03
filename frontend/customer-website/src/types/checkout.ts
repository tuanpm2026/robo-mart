export interface ShippingFormData {
  fullName: string
  street: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface PaymentFormData {
  cardholderName: string
  cardNumber: string
  expiry: string
  cvv: string
}

export type CheckoutErrorType = 'PAYMENT_FAILED' | 'INVENTORY_FAILED' | 'UNKNOWN'

export interface CheckoutError {
  type: CheckoutErrorType
  message: string
}

export function formatShippingAddress(data: ShippingFormData): string {
  return `${data.fullName}, ${data.street}, ${data.city}, ${data.state} ${data.postalCode}, ${data.country}`
}
