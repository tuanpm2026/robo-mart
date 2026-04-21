<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import Skeleton from 'primevue/skeleton'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { OrderStateMachine } from '@robo-mart/shared'
import { useOrderStore } from '@/stores/useOrderStore'
import type { OrderStatus } from '@/types/order'

const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()
const confirm = useConfirm()
const toast = useToast()

const isCancelling = ref(false)

const orderId = computed(() => Number(route.params.id))

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Order received',
  PAYMENT_PENDING: 'Awaiting payment',
  INVENTORY_RESERVING: 'Processing order',
  PAYMENT_PROCESSING: 'Processing payment',
  CONFIRMED: 'Order confirmed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  PAYMENT_REFUNDING: 'Cancelling',
  INVENTORY_RELEASING: 'Cancelling',
  CANCELLED: 'Cancelled',
}

const STATUS_SEVERITY: Record<OrderStatus, 'success' | 'warn' | 'danger' | 'info' | 'secondary'> = {
  PENDING: 'warn',
  PAYMENT_PENDING: 'warn',
  INVENTORY_RESERVING: 'warn',
  PAYMENT_PROCESSING: 'warn',
  CONFIRMED: 'success',
  SHIPPED: 'info',
  DELIVERED: 'info',
  PAYMENT_REFUNDING: 'warn',
  INVENTORY_RELEASING: 'warn',
  CANCELLED: 'danger',
}

const PAYMENT_PENDING_MESSAGE = "We're confirming your payment — we'll notify you when it's done"

const isCancellable = computed(() => {
  const status = orderStore.currentOrder?.status
  return status === 'PENDING' || status === 'CONFIRMED'
})

const isPendingPayment = computed(() =>
  orderStore.currentOrder?.status === 'PAYMENT_PROCESSING'
)

function formatDate(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function formatPrice(amount: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

function onCancelOrder() {
  confirm.require({
    message: 'Are you sure you want to cancel this order? This action cannot be undone.',
    header: 'Cancel Order',
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Keep Order',
    acceptLabel: 'Cancel Order',
    acceptClass: 'p-button-danger',
    accept: async () => {
      isCancelling.value = true
      try {
        await orderStore.cancelOrder(orderId.value)
        toast.add({
          severity: 'success',
          summary: 'Order cancelled',
          detail: 'Your order has been cancelled successfully.',
          life: 4000,
        })
      } catch {
        toast.add({
          severity: 'error',
          summary: 'Cancellation failed',
          detail: 'Could not cancel your order. Please try again.',
          life: 5000,
        })
      } finally {
        isCancelling.value = false
      }
    },
  })
}

onMounted(async () => {
  if (isNaN(orderId.value)) {
    router.push('/orders')
    return
  }
  await orderStore.fetchOrder(orderId.value)
  if (orderStore.error) {
    router.push('/orders')
  }
})
</script>

<template>
  <div class="order-detail">
    <ConfirmDialog />

    <RouterLink to="/orders" class="order-detail__back">
      <i class="pi pi-arrow-left" />
      Back to My Orders
    </RouterLink>

    <!-- Loading skeleton -->
    <div v-if="orderStore.isLoading && !orderStore.currentOrder" class="order-detail__skeleton">
      <div class="order-detail__skeleton-header">
        <Skeleton width="200px" height="2rem" />
        <Skeleton width="120px" height="1.8rem" border-radius="12px" />
      </div>
      <Skeleton width="100%" height="120px" border-radius="8px" />
      <Skeleton width="100%" height="200px" border-radius="8px" />
    </div>

    <!-- Order detail content -->
    <template v-else-if="orderStore.currentOrder">
      <div class="order-detail__header">
        <div class="order-detail__title-row">
          <h1 class="order-detail__title">Order #{{ orderStore.currentOrder.id }}</h1>
          <Tag
            :value="STATUS_LABEL[orderStore.currentOrder.status]"
            :severity="STATUS_SEVERITY[orderStore.currentOrder.status]"
          />
        </div>
        <p class="order-detail__date">Placed on {{ formatDate(orderStore.currentOrder.createdAt) }}</p>
      </div>

      <!-- Payment pending reassurance message -->
      <div v-if="isPendingPayment" class="order-detail__payment-notice" role="status">
        <i class="pi pi-clock order-detail__payment-notice-icon" aria-hidden="true" />
        <p>{{ PAYMENT_PENDING_MESSAGE }}</p>
      </div>

      <!-- Order state machine visualization -->
      <div class="order-detail__state-machine">
        <OrderStateMachine
          :status="orderStore.currentOrder.status"
          :status-history="orderStore.currentOrder.statusHistory"
          :cancellation-reason="orderStore.currentOrder.cancellationReason ?? undefined"
        />
      </div>

      <!-- Order items -->
      <div class="order-detail__section">
        <h2 class="order-detail__section-title">Order Items</h2>
        <table class="order-detail__items-table" aria-label="Order items">
          <thead>
            <tr>
              <th class="order-detail__items-th">Product</th>
              <th class="order-detail__items-th order-detail__items-th--right">Qty</th>
              <th class="order-detail__items-th order-detail__items-th--right">Unit Price</th>
              <th class="order-detail__items-th order-detail__items-th--right">Subtotal</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in orderStore.currentOrder.items"
              :key="item.productId"
              class="order-detail__items-row"
            >
              <td class="order-detail__items-td">{{ item.productName }}</td>
              <td class="order-detail__items-td order-detail__items-td--right">{{ item.quantity }}</td>
              <td class="order-detail__items-td order-detail__items-td--right">{{ formatPrice(item.unitPrice) }}</td>
              <td class="order-detail__items-td order-detail__items-td--right">{{ formatPrice(item.subtotal) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <td colspan="3" class="order-detail__total-label">Total</td>
              <td class="order-detail__total-value">{{ formatPrice(orderStore.currentOrder.totalAmount) }}</td>
            </tr>
          </tfoot>
        </table>
      </div>

      <!-- Order summary -->
      <div class="order-detail__section">
        <h2 class="order-detail__section-title">Order Summary</h2>
        <div class="order-detail__summary-grid">
          <div class="order-detail__summary-item">
            <span class="order-detail__summary-label">Shipping Address</span>
            <span class="order-detail__summary-value">
              {{ orderStore.currentOrder.shippingAddress || 'Not specified' }}
            </span>
          </div>
          <div class="order-detail__summary-item">
            <span class="order-detail__summary-label">Payment Status</span>
            <span class="order-detail__summary-value">
              {{ STATUS_LABEL[orderStore.currentOrder.status] }}
            </span>
          </div>
          <div v-if="orderStore.currentOrder.cancellationReason" class="order-detail__summary-item">
            <span class="order-detail__summary-label">Cancellation Reason</span>
            <span class="order-detail__summary-value">{{ orderStore.currentOrder.cancellationReason }}</span>
          </div>
        </div>
      </div>

      <!-- Actions -->
      <div v-if="isCancellable" class="order-detail__actions">
        <Button
          label="Cancel Order"
          severity="danger"
          outlined
          :loading="isCancelling"
          :disabled="isCancelling"
          @click="onCancelOrder"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.order-detail {
  padding: 24px 0;
  max-width: 800px;
}

.order-detail__back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-primary-600);
  text-decoration: none;
  margin-bottom: 24px;
}

.order-detail__back:hover {
  text-decoration: underline;
}

.order-detail__skeleton {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-detail__skeleton-header {
  display: flex;
  align-items: center;
  gap: 16px;
}

.order-detail__header {
  margin-bottom: 24px;
}

.order-detail__title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.order-detail__title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-gray-900);
  margin: 0;
}

.order-detail__date {
  font-size: 14px;
  color: var(--color-gray-500);
  margin: 0;
}

.order-detail__payment-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 16px;
  background: var(--p-amber-50, #fffbeb);
  border: 1px solid var(--p-amber-200, #fde68a);
  border-radius: 8px;
  margin-bottom: 20px;
  font-size: 14px;
  color: var(--p-amber-800, #92400e);
}

.order-detail__payment-notice-icon {
  font-size: 18px;
  color: var(--p-amber-500, #f59e0b);
  flex-shrink: 0;
  margin-top: 1px;
}

.order-detail__state-machine {
  background: #ffffff;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 24px;
}

.order-detail__section {
  background: #ffffff;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 24px;
}

.order-detail__section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-800);
  margin: 0 0 16px;
}

.order-detail__items-table {
  width: 100%;
  border-collapse: collapse;
}

.order-detail__items-th {
  padding: 8px 12px;
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-gray-500);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--color-gray-200);
}

.order-detail__items-th--right {
  text-align: right;
}

.order-detail__items-td {
  padding: 12px;
  font-size: 14px;
  color: var(--color-gray-700);
  border-bottom: 1px solid var(--color-gray-100);
}

.order-detail__items-td--right {
  text-align: right;
}

.order-detail__items-row:last-child .order-detail__items-td {
  border-bottom: 1px solid var(--color-gray-200);
}

.order-detail__total-label {
  padding: 12px;
  text-align: right;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-gray-700);
}

.order-detail__total-value {
  padding: 12px;
  text-align: right;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-gray-900);
}

.order-detail__summary-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-detail__summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.order-detail__summary-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-gray-500);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.order-detail__summary-value {
  font-size: 14px;
  color: var(--color-gray-700);
}

.order-detail__actions {
  display: flex;
  gap: 12px;
}
</style>
