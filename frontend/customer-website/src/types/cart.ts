export interface CartItem {
  productId: number
  productName: string
  price: number
  quantity: number
  subtotal: number
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
