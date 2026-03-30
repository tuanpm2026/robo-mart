<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { useRouter } from 'vue-router'
import { EmptyState } from '@robo-mart/shared'
import { useCartStore } from '@/stores/useCartStore'
import CartItemComponent from '@/components/cart/CartItem.vue'
import CartSummary from '@/components/cart/CartSummary.vue'

const cartStore = useCartStore()
const toast = useToast()
const router = useRouter()
const loadingItems = reactive(new Set<number>())

onMounted(() => {
  cartStore.fetchCart()
})

async function onUpdateQuantity(productId: number, quantity: number) {
  if (loadingItems.has(productId)) return
  loadingItems.add(productId)
  try {
    await cartStore.updateItemQuantity(productId, quantity)
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Update failed',
      detail: 'Could not update quantity. Please try again.',
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
    toast.add({
      severity: 'success',
      summary: 'Item removed',
      detail: 'Item has been removed from your cart.',
      life: 3000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Remove failed',
      detail: 'Could not remove item. Please try again.',
      life: 5000,
    })
  } finally {
    loadingItems.delete(productId)
  }
}

function onBrowseProducts() {
  router.push('/')
}
</script>

<template>
  <div class="cart">
    <h1 class="cart__title">Shopping Cart</h1>

    <!-- Loading skeleton -->
    <div v-if="cartStore.isLoading" class="cart__skeleton">
      <div v-for="n in 3" :key="n" class="cart__skeleton-item">
        <Skeleton width="80px" height="80px" />
        <div class="cart__skeleton-details">
          <Skeleton width="60%" height="1.2rem" />
          <Skeleton width="30%" height="1rem" />
        </div>
        <Skeleton width="120px" height="2.5rem" />
        <Skeleton width="80px" height="1.2rem" />
      </div>
    </div>

    <!-- Empty state -->
    <EmptyState
      v-else-if="cartStore.items.length === 0"
      variant="cart"
      @action="onBrowseProducts"
    />

    <!-- Cart content -->
    <div v-else class="cart__content">
      <div class="cart__items">
        <CartItemComponent
          v-for="item in cartStore.items"
          :key="item.productId"
          :item="item"
          :loading="loadingItems.has(item.productId)"
          @update:quantity="onUpdateQuantity"
          @remove="onRemoveItem"
        />
      </div>

      <CartSummary :totalItems="cartStore.totalItems" :totalPrice="cartStore.totalPrice" />
    </div>
  </div>
</template>

<style scoped>
.cart {
  padding: 16px 0;
}

.cart__title {
  font-size: 30px;
  font-weight: 700;
  color: var(--color-gray-900);
  margin: 0 0 24px;
}

.cart__content {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 32px;
  align-items: start;
}

.cart__items {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cart__skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cart__skeleton-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
}

.cart__skeleton-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
