<script setup lang="ts">
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useAuthStore } from '@/stores/useAuthStore'

defineProps<{
  totalItems: number
  totalPrice: number
}>()

const router = useRouter()
const authStore = useAuthStore()

function handleCheckout() {
  if (authStore.isAuthenticated) {
    router.push('/checkout')
  } else {
    authStore.login(undefined, undefined, '/checkout')
  }
}
</script>

<template>
  <aside class="cart-summary" aria-label="Cart summary">
    <h2 class="cart-summary__title">Order Summary</h2>

    <div class="cart-summary__row">
      <span>Items ({{ totalItems }})</span>
      <span class="cart-summary__price">${{ totalPrice.toFixed(2) }}</span>
    </div>

    <div class="cart-summary__divider" />

    <div class="cart-summary__row cart-summary__row--total">
      <span>Total</span>
      <span class="cart-summary__total">${{ totalPrice.toFixed(2) }}</span>
    </div>

    <div class="cart-summary__actions">
      <Button
        label="Proceed to Checkout"
        severity="primary"
        class="cart-summary__checkout-btn"
        @click="handleCheckout"
      />
      <Button
        label="Continue Shopping"
        severity="secondary"
        outlined
        class="cart-summary__continue-btn"
        @click="router.push('/')"
      />
    </div>
  </aside>
</template>

<style scoped>
.cart-summary {
  padding: 24px;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  background: #ffffff;
}

.cart-summary__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0 0 16px;
}

.cart-summary__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: var(--color-gray-600);
  padding: 4px 0;
}

.cart-summary__row--total {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-gray-900);
}

.cart-summary__price {
  font-weight: 500;
}

.cart-summary__total {
  color: var(--color-primary-600);
}

.cart-summary__divider {
  height: 1px;
  background: var(--color-gray-200);
  margin: 12px 0;
}

.cart-summary__actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 20px;
}

.cart-summary__checkout-btn,
.cart-summary__continue-btn {
  width: 100%;
}
</style>
