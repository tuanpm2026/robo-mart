export interface CartItem {
  lineId?: string
  productId: number
  productName: string
  price: number
  quantity: number
  subtotal: number
}

export interface CheckoutCartItem {
  productId: number
  quantity: number
  lineId: string
}

export interface Cart {
  cartId: string
  items: CartItem[]
  totalItems: number
  totalPrice: number
}

export interface AddToCartRequest {
  productId: number
  productName: string
  price: number
  quantity: number
}

export interface UpdateQuantityRequest {
  quantity: number
}
