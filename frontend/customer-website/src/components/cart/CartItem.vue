<script setup lang="ts">
import { onUnmounted } from 'vue'
import InputNumber from 'primevue/inputnumber'
import Button from 'primevue/button'
import type { CartItem } from '@/types/cart'

defineProps<{
  item: CartItem
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:quantity': [productId: number, quantity: number]
  remove: [productId: number]
}>()

let debounceTimer: ReturnType<typeof setTimeout> | undefined

onUnmounted(() => {
  clearTimeout(debounceTimer)
})

function onQuantityChange(productId: number, event: { value: number | null }) {
  if (event.value && event.value >= 1) {
    clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      emit('update:quantity', productId, event.value!)
    }, 300)
  }
}
</script>

<template>
  <article class="cart-item" :aria-label="`${item.productName}, quantity ${item.quantity}`">
    <div class="cart-item__image-wrapper">
      <div class="cart-item__image-placeholder" aria-hidden="true">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
          <rect
            x="3"
            y="3"
            width="18"
            height="18"
            rx="2"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <circle cx="8.5" cy="8.5" r="1.5" fill="currentColor" />
          <path
            d="M21 15L16 10L5 21"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
          />
        </svg>
      </div>
    </div>

    <div class="cart-item__details">
      <h3 class="cart-item__name">{{ item.productName }}</h3>
      <p class="cart-item__price">${{ item.price.toFixed(2) }}</p>
    </div>

    <div class="cart-item__quantity">
      <label :for="`qty-${item.productId}`" class="cart-item__quantity-label">Qty</label>
      <InputNumber
        :id="`qty-${item.productId}`"
        :modelValue="item.quantity"
        :min="1"
        :max="999"
        :disabled="loading"
        showButtons
        buttonLayout="horizontal"
        incrementButtonIcon="pi pi-plus"
        decrementButtonIcon="pi pi-minus"
        class="cart-item__quantity-input"
        @update:modelValue="
          (val: number | null) => onQuantityChange(item.productId, { value: val })
        "
      />
    </div>

    <p class="cart-item__subtotal">${{ item.subtotal.toFixed(2) }}</p>

    <Button
      icon="pi pi-times"
      severity="danger"
      text
      rounded
      :disabled="loading"
      :aria-label="`Remove ${item.productName} from cart`"
      class="cart-item__remove"
      @click="emit('remove', item.productId)"
    />
  </article>
</template>

<style scoped>
.cart-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  background: #ffffff;
}

.cart-item__image-wrapper {
  flex-shrink: 0;
  width: 80px;
  height: 80px;
  border-radius: 4px;
  overflow: hidden;
}

.cart-item__image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-gray-50);
  color: var(--color-gray-300);
}

.cart-item__details {
  flex: 1;
  min-width: 0;
}

.cart-item__name {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cart-item__price {
  font-size: 14px;
  color: var(--color-gray-500);
  margin: 0;
}

.cart-item__quantity {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.cart-item__quantity-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-600);
}

.cart-item__quantity-input {
  width: 120px;
}

.cart-item__subtotal {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0;
  min-width: 80px;
  text-align: right;
  flex-shrink: 0;
}

.cart-item__remove {
  flex-shrink: 0;
}
</style>
