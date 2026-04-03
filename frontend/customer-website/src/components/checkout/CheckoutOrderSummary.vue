<script setup lang="ts">
import type { CartItem } from '@/types/cart'

defineProps<{
  items: CartItem[]
  totalPrice: number
}>()
</script>

<template>
  <aside class="cos" aria-label="Order summary">
    <h3 class="cos__title">Order Summary</h3>
    <ul class="cos__items" aria-label="Cart items">
      <li v-for="item in items" :key="item.productId" class="cos__item">
        <span class="cos__item-name">{{ item.productName }}</span>
        <span class="cos__item-detail">× {{ item.quantity }}</span>
        <span class="cos__item-price">${{ item.subtotal.toFixed(2) }}</span>
      </li>
    </ul>
    <div class="cos__divider" />
    <div class="cos__total">
      <span>Total</span>
      <span class="cos__total-price">${{ totalPrice.toFixed(2) }}</span>
    </div>
  </aside>
</template>

<style scoped>
.cos {
  padding: 20px;
  border: 1px solid var(--color-gray-200, #e5e7eb);
  border-radius: 8px;
  background: #fff;
  position: sticky;
  top: 24px;
}
.cos__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 12px;
}
.cos__items {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cos__item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-gray-700, #374151);
}
.cos__item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cos__item-detail {
  color: var(--color-gray-500, #6b7280);
}
.cos__item-price {
  font-weight: 500;
  min-width: 60px;
  text-align: right;
}
.cos__divider {
  height: 1px;
  background: var(--color-gray-200, #e5e7eb);
  margin: 12px 0;
}
.cos__total {
  display: flex;
  justify-content: space-between;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-gray-900, #111827);
}
.cos__total-price {
  color: var(--p-primary-color, #2563eb);
}
</style>
