<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import { useOrderStore } from '@/stores/useOrderStore'
import { useCheckoutStore } from '@/stores/useCheckoutStore'

const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()
const checkoutStore = useCheckoutStore()

onMounted(async () => {
  const rawParam = Array.isArray(route.params.orderId)
    ? route.params.orderId[0]
    : route.params.orderId
  const raw = rawParam ?? ''
  const orderId = parseInt(raw, 10)
  if (isNaN(orderId)) {
    router.replace('/orders')
    return
  }
  checkoutStore.$reset()
  await orderStore.fetchOrder(orderId)
})
</script>

<template>
  <div class="ocv">
    <!-- Loading -->
    <div v-if="orderStore.isLoading" class="ocv__skeleton">
      <Skeleton width="64px" height="64px" border-radius="50%" />
      <Skeleton width="260px" height="2rem" />
      <Skeleton width="160px" height="1rem" />
      <div class="ocv__skeleton-items">
        <Skeleton v-for="n in 2" :key="n" height="1rem" />
      </div>
      <div class="ocv__skeleton-actions">
        <Skeleton width="160px" height="2.5rem" />
        <Skeleton width="160px" height="2.5rem" />
      </div>
    </div>

    <!-- Success -->
    <div v-else-if="orderStore.currentOrder" class="ocv__content">
      <div class="ocv__hero">
        <template v-if="orderStore.currentOrder.status === 'PAYMENT_PENDING'">
          <div class="ocv__icon ocv__icon--pending" aria-hidden="true">⏳</div>
          <h1 class="ocv__heading">Order Received!</h1>
          <p class="ocv__order-number">Order #{{ orderStore.currentOrder.id }}</p>
          <p class="ocv__payment-pending">
            Order received. Payment is being processed — we'll notify you when confirmed.
          </p>
        </template>
        <template v-else>
          <div class="ocv__icon" aria-hidden="true">✓</div>
          <h1 class="ocv__heading">Order Confirmed!</h1>
          <p class="ocv__order-number">Order #{{ orderStore.currentOrder.id }}</p>
          <p class="ocv__delivery">Estimated delivery: 3–5 business days</p>
        </template>
      </div>

      <div class="ocv__summary">
        <h2 class="ocv__summary-title">Order Summary</h2>
        <ul class="ocv__items">
          <li v-for="item in orderStore.currentOrder.items" :key="item.productId" class="ocv__item">
            <span class="ocv__item-name">{{ item.productName }}</span>
            <span class="ocv__item-qty">× {{ item.quantity }}</span>
            <span class="ocv__item-price">${{ item.subtotal.toFixed(2) }}</span>
          </li>
        </ul>
        <div class="ocv__total">
          <strong>Total: ${{ orderStore.currentOrder.totalAmount.toFixed(2) }}</strong>
        </div>
      </div>

      <div class="ocv__actions">
        <Button
          label="Track Order"
          severity="primary"
          class="ocv__track-btn"
          @click="router.push(`/orders/${orderStore.currentOrder!.id}`)"
        />
        <Button label="Continue Shopping" severity="secondary" outlined @click="router.push('/')" />
      </div>
    </div>

    <!-- Error -->
    <div v-else class="ocv__error">
      <p>Could not load order details.</p>
      <Button label="View My Orders" severity="primary" outlined @click="router.push('/orders')" />
    </div>
  </div>
</template>

<style scoped>
.ocv {
  max-width: 640px;
  margin: 48px auto;
  padding: 0 16px;
  text-align: center;
}
.ocv__skeleton {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.ocv__skeleton-items {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ocv__skeleton-actions {
  display: flex;
  gap: 16px;
}
.ocv__content {
  display: flex;
  flex-direction: column;
  gap: 32px;
}
.ocv__hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.ocv__icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #dcfce7;
  color: #16a34a;
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
  animation: ocv-pop 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275) both;
}
.ocv__icon--pending {
  background: #fef9c3;
  color: #a16207;
}
.ocv__payment-pending {
  font-size: 14px;
  color: #a16207;
  margin: 0;
  padding: 8px 16px;
  background: #fef9c3;
  border: 1px solid #fde047;
  border-radius: 8px;
}
.ocv__heading {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-gray-900, #111827);
  margin: 0;
}
.ocv__order-number {
  font-size: 16px;
  color: var(--color-gray-600, #4b5563);
  margin: 0;
}
.ocv__delivery {
  font-size: 14px;
  color: var(--color-gray-500, #6b7280);
  margin: 0;
}
.ocv__summary {
  padding: 20px;
  border: 1px solid var(--color-gray-200, #e5e7eb);
  border-radius: 8px;
  background: #fff;
  text-align: left;
}
.ocv__summary-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 12px;
}
.ocv__items {
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ocv__item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--color-gray-700, #374151);
}
.ocv__item-name {
  flex: 1;
}
.ocv__item-qty {
  color: var(--color-gray-500, #6b7280);
}
.ocv__item-price {
  font-weight: 500;
}
.ocv__total {
  font-size: 16px;
  text-align: right;
  color: var(--color-gray-900, #111827);
  border-top: 1px solid var(--color-gray-200, #e5e7eb);
  padding-top: 12px;
}
.ocv__actions {
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
}
.ocv__track-btn {
  min-width: 160px;
}
.ocv__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  color: var(--color-gray-600, #4b5563);
}
@keyframes ocv-pop {
  from {
    transform: scale(0.5);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
