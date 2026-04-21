<script setup lang="ts">
import { reactive } from 'vue'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import CartItemComponent from '@/components/cart/CartItem.vue'
import { useCartStore } from '@/stores/useCartStore'

const emit = defineEmits<{ continue: [] }>()

const cartStore = useCartStore()
const toast = useToast()
const loadingItems = reactive(new Set<number>())

async function onUpdateQuantity(productId: number, quantity: number) {
  if (loadingItems.has(productId)) return
  loadingItems.add(productId)
  try {
    await cartStore.updateItemQuantity(productId, quantity)
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Update failed',
      detail: 'Could not update quantity.',
      life: 5000,
    })
  } finally {
    loadingItems.delete(productId)
  }
}

async function onRemoveItem(productId: number) {
  if (loadingItems.has(productId)) return
  loadingItems.add(productId)
  try {
    await cartStore.removeCartItem(productId)
    toast.add({ severity: 'success', summary: 'Item removed', life: 3000 })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Remove failed',
      detail: 'Could not remove item.',
      life: 5000,
    })
  } finally {
    loadingItems.delete(productId)
  }
}
</script>

<template>
  <div class="scr">
    <h2 class="scr__heading">Review Your Items</h2>

    <div v-if="cartStore.items.length === 0" class="scr__empty">
      <p>Your cart is empty.</p>
    </div>

    <div v-else class="scr__items">
      <CartItemComponent
        v-for="item in cartStore.items"
        :key="item.productId"
        :item="item"
        :loading="loadingItems.has(item.productId)"
        @update:quantity="onUpdateQuantity"
        @remove="onRemoveItem"
      />
    </div>

    <div class="scr__actions">
      <Button
        label="Continue to Shipping →"
        severity="primary"
        :disabled="cartStore.items.length === 0"
        class="scr__continue"
        @click="emit('continue')"
      />
    </div>
  </div>
</template>

<style scoped>
.scr__heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 20px;
}
.scr__items {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}
.scr__empty {
  padding: 24px;
  text-align: center;
  color: var(--color-gray-500, #6b7280);
}
.scr__actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--color-gray-200, #e5e7eb);
}
.scr__continue {
  min-width: 200px;
}
</style>
