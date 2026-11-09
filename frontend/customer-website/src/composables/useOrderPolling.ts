import { onUnmounted, watch } from 'vue'
import { useOrderStore } from '@/stores/useOrderStore'
import { useCheckoutStore } from '@/stores/useCheckoutStore'

const PENDING = new Set([
  'PENDING',
  'PAYMENT_PENDING',
  'INVENTORY_RESERVING',
  'PAYMENT_PROCESSING',
  'CANCELLATION_PENDING',
  'PAYMENT_REFUNDING',
  'INVENTORY_RELEASING',
])

/** Follow eventual checkout/cancellation outcomes for at most two minutes per visit. */
export function useOrderPolling(orderId: () => number) {
  const orders = useOrderStore()
  const checkout = useCheckoutStore()
  let timer: ReturnType<typeof setTimeout> | undefined
  let attempts = 0
  let deadline = Date.now() + 120000
  let delay = 2000
  let stopped = false
  let inFlight = false
  function schedule() {
    if (
      stopped ||
      inFlight ||
      timer ||
      attempts >= 60 ||
      Date.now() >= deadline ||
      !PENDING.has(orders.currentOrder?.status ?? '')
    )
      return
    timer = setTimeout(
      async () => {
        timer = undefined
        inFlight = true
        attempts++
        const id = orderId()
        try {
          await orders.fetchOrder(id, true)
          delay = orders.error ? Math.min(delay * 2, 30000) : 2000
          if (!stopped && id === orderId() && orders.currentOrder)
            await checkout.reconcileCart(orders.currentOrder)
        } finally {
          inFlight = false
          schedule()
        }
      },
      Math.min(delay, Math.max(0, deadline - Date.now())),
    )
  }
  watch(orderId, () => {
    clearTimeout(timer)
    timer = undefined
    attempts = 0
    delay = 2000
    deadline = Date.now() + 120000
  })
  watch(
    () => orders.currentOrder?.status,
    (status, previous) => {
      if (PENDING.has(status ?? '') && !PENDING.has(previous ?? '')) {
        attempts = 0
        deadline = Date.now() + 120000
      }
      schedule()
    },
    { immediate: true },
  )
  onUnmounted(() => {
    stopped = true
    clearTimeout(timer)
  })
}
