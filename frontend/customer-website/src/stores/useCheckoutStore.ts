import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useRouter } from 'vue-router'
import type { AxiosError } from 'axios'
import { placeOrder as placeOrderApi } from '@/api/orderApi'
import { useCartStore } from '@/stores/useCartStore'
import { useAuthStore } from '@/stores/useAuthStore'
import { clearAttempt, readAttempt, saveAttempt, type CheckoutAttempt } from './checkoutAttempt'
import type { ShippingFormData, PaymentFormData, CheckoutError } from '@/types/checkout'
import type { OrderStatus } from '@/types/order'
import { formatShippingAddress } from '@/types/checkout'

const SAGA_MESSAGES = ['Creating your order...', 'Reserving items...', 'Processing payment...']
const ACCEPTED = new Set<OrderStatus>(['CONFIRMED', 'PAYMENT_PENDING', 'SHIPPED', 'DELIVERED'])

interface ApiErrorBody {
  error?: { code?: string; message?: string }
}

export const useCheckoutStore = defineStore('checkout', () => {
  const router = useRouter()
  const currentStep = ref<1 | 2 | 3 | 4>(1)
  const shippingData = ref<ShippingFormData | null>(null)
  const paymentData = ref<PaymentFormData | null>(null)
  const isPlacingOrder = ref(false)
  const error = ref<CheckoutError | null>(null)
  const sagaMessage = ref(SAGA_MESSAGES[0])
  const attempt = ref<CheckoutAttempt | null>(null)
  const cartCleanupPending = ref(false)
  const hasPendingAttempt = computed(() => attempt.value !== null)
  const checkoutItems = computed(() => attempt.value?.items ?? useCartStore().items)
  const checkoutTotal = computed(() =>
    checkoutItems.value.reduce((sum, item) => sum + item.subtotal, 0),
  )

  function nextStep() {
    if (currentStep.value < 4) currentStep.value = (currentStep.value + 1) as 1 | 2 | 3 | 4
  }

  function prevStep() {
    if (!hasPendingAttempt.value && currentStep.value > 1) {
      currentStep.value = (currentStep.value - 1) as 1 | 2 | 3 | 4
    }
  }

  function setShippingData(data: ShippingFormData) {
    shippingData.value = data
  }
  function setPaymentData(data: PaymentFormData) {
    paymentData.value = data
  }

  function restoreAttempt() {
    const userId = useAuthStore().user?.id
    attempt.value = userId ? readAttempt(userId) : null
    if (attempt.value) {
      shippingData.value = { ...attempt.value.shipping }
      currentStep.value = 4
    }
  }

  function forgetAttempt(userId: string) {
    clearAttempt(userId)
    attempt.value = null
    cartCleanupPending.value = false
  }

  async function reconcileCart(order: { id: number; status: OrderStatus }) {
    const userId = useAuthStore().user?.id
    if (!userId) return
    // Only the matching saved checkout can acknowledge its own cart snapshot.
    const saved = readAttempt(userId)
    if (!saved || saved.orderId !== order.id) return
    if (order.status === 'CANCELLED' || order.status === 'INVENTORY_RELEASING') {
      forgetAttempt(userId)
      return
    }
    if (!ACCEPTED.has(order.status)) return
    cartCleanupPending.value = true
    try {
      await useCartStore().completeCheckout(
        order.id,
        saved.items.map(({ productId, quantity, lineId }) => ({
          productId,
          quantity,
          lineId,
        })),
      )
      forgetAttempt(userId)
    } catch {
      // The order was accepted. Retain its receipt for retry, never report a failed purchase.
    }
  }

  async function placeOrder() {
    if (isPlacingOrder.value) return
    const userId = useAuthStore().user?.id
    if (!userId) return
    error.value = null
    isPlacingOrder.value = true
    sagaMessage.value = SAGA_MESSAGES[0]
    let msgIndex = 0
    const intervalId = setInterval(() => {
      msgIndex = (msgIndex + 1) % SAGA_MESSAGES.length
      sagaMessage.value = SAGA_MESSAGES[msgIndex]
    }, 1500)

    try {
      restoreAttempt()
      if (!attempt.value) {
        if (!shippingData.value || !paymentData.value) return
        const cart = useCartStore()
        // Obtain server-issued line identities before freezing the checkout snapshot.
        if (cart.items.some((item) => !item.lineId)) await cart.fetchCart()
        if (!cart.items.length || cart.items.some((item) => !item.lineId)) {
          throw new Error('Please refresh your cart before checking out.')
        }
        const items = cart.items.map((item) => ({ ...item, lineId: item.lineId! }))
        attempt.value = {
          key: crypto.randomUUID(),
          shipping: { ...shippingData.value },
          items,
          request: {
            items: items.map((item) => ({
              productId: String(item.productId),
              productName: item.productName,
              quantity: item.quantity,
              unitPrice: item.price,
            })),
            shippingAddress: formatShippingAddress(shippingData.value),
          },
        }
        saveAttempt(userId, attempt.value)
      }
      // A retry always sends the original payload, even if the cart changed in another tab.
      const result = await placeOrderApi(attempt.value.request, attempt.value.key)
      attempt.value.orderId = result.data.id
      saveAttempt(userId, attempt.value)
      await reconcileCart(result.data)
      await router.push(`/order-confirmation/${result.data.id}`)
    } catch (err: unknown) {
      const axiosErr = err as AxiosError<ApiErrorBody>
      const errorCode = axiosErr.response?.data?.error?.code
      const message = axiosErr.response?.data?.error?.message ?? 'Order processing failed'
      if (errorCode === 'ORDER_INVENTORY_FAILED') {
        forgetAttempt(userId)
        error.value = { type: 'INVENTORY_FAILED', message }
      } else if (errorCode === 'ORDER_PAYMENT_FAILED') {
        forgetAttempt(userId)
        error.value = {
          type: 'PAYMENT_FAILED',
          message:
            'Payment was declined and this order was cancelled. Your cart is saved — please try again.',
        }
        currentStep.value = 3
      } else if (axiosErr.response?.status === 400 || axiosErr.response?.status === 422) {
        // Bean validation rejects the request before creating an order, so edits can start a new attempt.
        forgetAttempt(userId)
        error.value = {
          type: 'UNKNOWN',
          message: 'Please review your checkout details and try again.',
        }
      } else {
        // Unknown failures may occur after commit: keep the key and frozen payload across reloads.
        error.value = {
          type: 'UNKNOWN',
          message:
            'We could not confirm the result. Retry to check the same order, or review My Orders.',
        }
      }
    } finally {
      clearInterval(intervalId)
      isPlacingOrder.value = false
    }
  }

  function $reset() {
    currentStep.value = 1
    shippingData.value = null
    paymentData.value = null
    isPlacingOrder.value = false
    error.value = null
    sagaMessage.value = SAGA_MESSAGES[0]
    attempt.value = null
    cartCleanupPending.value = false
    // Persisted attempts survive component resets; only definitive outcomes retire them.
  }

  return {
    currentStep,
    shippingData,
    paymentData,
    isPlacingOrder,
    error,
    sagaMessage,
    hasPendingAttempt,
    checkoutItems,
    checkoutTotal,
    cartCleanupPending,
    nextStep,
    prevStep,
    setShippingData,
    setPaymentData,
    restoreAttempt,
    reconcileCart,
    placeOrder,
    $reset,
  }
})
