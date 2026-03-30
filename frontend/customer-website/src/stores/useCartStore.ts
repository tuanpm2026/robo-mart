import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getCart, addToCart, updateQuantity, removeItem, mergeCart } from '@/api/cartApi'
import { clearAnonymousIdCache } from '@/api/client'
import type { CartItem, AddToCartRequest } from '@/types/cart'

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const totalItems = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))
  const totalPrice = computed(() => items.value.reduce((sum, item) => sum + item.subtotal, 0))

  async function fetchCart() {
    isLoading.value = true
    error.value = null
    try {
      const response = await getCart()
      items.value = response.data.items
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load cart'
    } finally {
      isLoading.value = false
    }
  }

  async function addItem(request: AddToCartRequest) {
    const previousItems = [...items.value]
    const existing = items.value.find((i) => i.productId === request.productId)
    if (existing) {
      existing.quantity += request.quantity
      existing.subtotal = existing.price * existing.quantity
    } else {
      items.value.push({
        productId: request.productId,
        productName: request.productName,
        price: request.price,
        quantity: request.quantity,
        subtotal: request.price * request.quantity,
      })
    }
    try {
      const response = await addToCart(request)
      items.value = response.data.items
    } catch (err) {
      items.value = previousItems
      error.value = err instanceof Error ? err.message : 'Failed to add item'
      throw err
    }
  }

  async function updateItemQuantity(productId: number, quantity: number) {
    const item = items.value.find((i) => i.productId === productId)
    if (!item || item.quantity === quantity) return
    const previousItems = items.value.map((i) => ({ ...i }))
    item.quantity = quantity
    item.subtotal = item.price * quantity
    try {
      const response = await updateQuantity(productId, { quantity })
      items.value = response.data.items
    } catch (err) {
      items.value = previousItems
      error.value = err instanceof Error ? err.message : 'Failed to update quantity'
      throw err
    }
  }

  async function removeCartItem(productId: number) {
    const previousItems = [...items.value]
    items.value = items.value.filter((i) => i.productId !== productId)
    try {
      await removeItem(productId)
    } catch (err) {
      items.value = previousItems
      error.value = err instanceof Error ? err.message : 'Failed to remove item'
      throw err
    }
  }

  async function mergeAnonymousCart(): Promise<void> {
    const ANONYMOUS_KEY = 'robomart-user-id'
    const anonymousId = localStorage.getItem(ANONYMOUS_KEY)
    if (!anonymousId) return

    try {
      const response = await mergeCart(anonymousId)
      items.value = response.data.items
      localStorage.removeItem(ANONYMOUS_KEY)
      clearAnonymousIdCache()
    } catch (err) {
      console.error('Cart merge failed:', err)
    }
  }

  function $reset() {
    items.value = []
    isLoading.value = false
    error.value = null
  }

  return {
    items,
    isLoading,
    error,
    totalItems,
    totalPrice,
    fetchCart,
    addItem,
    updateItemQuantity,
    removeCartItem,
    mergeAnonymousCart,
    $reset,
  }
})
