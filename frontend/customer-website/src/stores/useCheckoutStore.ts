import { ref } from 'vue'
import { defineStore } from 'pinia'
import { useRouter } from 'vue-router'
import type { AxiosError } from 'axios'
import { placeOrder as placeOrderApi } from '@/api/orderApi'
import { useCartStore } from '@/stores/useCartStore'
import type { ShippingFormData, PaymentFormData, CheckoutError } from '@/types/checkout'
import { formatShippingAddress } from '@/types/checkout'

const SAGA_MESSAGES = [
  'Creating your order...',
  'Reserving items...',
  'Processing payment...',
]

interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
  }
}

export const useCheckoutStore = defineStore('checkout', () => {
  const router = useRouter()

  const currentStep = ref<1 | 2 | 3 | 4>(1)
  const shippingData = ref<ShippingFormData | null>(null)
  const paymentData = ref<PaymentFormData | null>(null)
  const isPlacingOrder = ref(false)
  const error = ref<CheckoutError | null>(null)
  const sagaMessage = ref(SAGA_MESSAGES[0])

  function nextStep() {
    if (currentStep.value < 4) {
      currentStep.value = (currentStep.value + 1) as 1 | 2 | 3 | 4
    }
  }

  function prevStep() {
    if (currentStep.value > 1) {
      currentStep.value = (currentStep.value - 1) as 1 | 2 | 3 | 4
    }
  }

  function setShippingData(data: ShippingFormData) {
    shippingData.value = data
  }

  function setPaymentData(data: PaymentFormData) {
    paymentData.value = data
  }

  async function placeOrder() {
    const cartStore = useCartStore()
    if (!shippingData.value) return

    error.value = null
    isPlacingOrder.value = true
    sagaMessage.value = SAGA_MESSAGES[0]

    let msgIndex = 0
    const intervalId = setInterval(() => {
      msgIndex = (msgIndex + 1) % SAGA_MESSAGES.length
      sagaMessage.value = SAGA_MESSAGES[msgIndex]
    }, 1500)

    try {
      const request = {
        items: cartStore.items.map((item) => ({
          productId: String(item.productId),
          productName: item.productName,
          quantity: item.quantity,
          unitPrice: item.price,
        })),
        shippingAddress: formatShippingAddress(shippingData.value),
      }

      const result = await placeOrderApi(request)
      cartStore.$reset()
      await router.push(`/order-confirmation/${result.data.id}`)
    } catch (err: unknown) {
      const axiosErr = err as AxiosError<ApiErrorBody>
      const errorCode = axiosErr.response?.data?.error?.code
      const errorMessage = axiosErr.response?.data?.error?.message ?? 'Order processing failed'

      if (errorCode === 'ORDER_INVENTORY_FAILED') {
        error.value = {
          type: 'INVENTORY_FAILED',
          message: errorMessage,
        }
      } else if (errorCode === 'ORDER_PAYMENT_FAILED') {
        error.value = {
          type: 'PAYMENT_FAILED',
          message: "Payment couldn't be processed. Your order is saved — try again or use a different method.",
        }
        currentStep.value = 3
      } else {
        error.value = {
          type: 'UNKNOWN',
          message: 'Something went wrong. Please try again.',
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
  }

  return {
    currentStep,
    shippingData,
    paymentData,
    isPlacingOrder,
    error,
    sagaMessage,
    nextStep,
    prevStep,
    setShippingData,
    setPaymentData,
    placeOrder,
    $reset,
  }
})
