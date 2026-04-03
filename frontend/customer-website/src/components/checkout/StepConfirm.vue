<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import { useCartStore } from '@/stores/useCartStore'

const emit = defineEmits<{ back: [] }>()
const checkoutStore = useCheckoutStore()
const cartStore = useCartStore()

const maskedCard = computed(() => {
  if (!checkoutStore.paymentData?.cardNumber) return '**** **** **** ****'
  const digits = checkoutStore.paymentData.cardNumber.replace(/\s/g, '')
  return `**** **** **** ${digits.slice(-4)}`
})
</script>

<template>
  <div class="sco">
    <h2 class="sco__heading">Confirm Your Order</h2>

    <section class="sco__section">
      <h3 class="sco__section-title">Items</h3>
      <ul class="sco__items">
        <li v-for="item in cartStore.items" :key="item.productId" class="sco__item">
          <span class="sco__item-name">{{ item.productName }}</span>
          <span class="sco__item-qty">× {{ item.quantity }}</span>
          <span class="sco__item-price">${{ item.subtotal.toFixed(2) }}</span>
        </li>
      </ul>
      <div class="sco__total">
        <strong>Total: ${{ cartStore.totalPrice.toFixed(2) }}</strong>
      </div>
    </section>

    <section class="sco__section">
      <h3 class="sco__section-title">Shipping Address</h3>
      <p class="sco__detail">{{ checkoutStore.shippingData?.fullName }}</p>
      <p class="sco__detail">{{ checkoutStore.shippingData?.street }}</p>
      <p class="sco__detail">
        {{ checkoutStore.shippingData?.city }}, {{ checkoutStore.shippingData?.state }}
        {{ checkoutStore.shippingData?.postalCode }}
      </p>
      <p class="sco__detail">{{ checkoutStore.shippingData?.country }}</p>
    </section>

    <section class="sco__section">
      <h3 class="sco__section-title">Payment</h3>
      <p class="sco__detail">{{ checkoutStore.paymentData?.cardholderName }}</p>
      <p class="sco__detail sco__card">{{ maskedCard }}</p>
    </section>

    <div class="sco__actions">
      <Button label="← Back to Payment" severity="secondary" outlined @click="emit('back')" />
      <Button
        label="Place Order"
        severity="primary"
        :loading="checkoutStore.isPlacingOrder"
        class="sco__place-order"
        @click="checkoutStore.placeOrder()"
      />
    </div>
  </div>
</template>

<style scoped>
.sco__heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 20px;
}
.sco__section {
  padding: 16px 0;
  border-bottom: 1px solid var(--color-gray-200, #e5e7eb);
  margin-bottom: 16px;
}
.sco__section-title {
  font-size: 13px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-gray-500, #6b7280);
  margin: 0 0 10px;
}
.sco__items {
  list-style: none;
  padding: 0;
  margin: 0 0 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sco__item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--color-gray-700, #374151);
}
.sco__item-name { flex: 1; }
.sco__item-qty { color: var(--color-gray-500, #6b7280); }
.sco__item-price { font-weight: 500; }
.sco__total {
  font-size: 15px;
  color: var(--color-gray-900, #111827);
  text-align: right;
}
.sco__detail {
  font-size: 14px;
  color: var(--color-gray-700, #374151);
  margin: 2px 0;
}
.sco__card {
  font-family: 'Courier New', monospace;
  letter-spacing: 2px;
}
.sco__actions {
  display: flex;
  justify-content: space-between;
  padding-top: 16px;
  gap: 12px;
}
.sco__place-order { min-width: 160px; }
</style>
