<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Rating from 'primevue/rating'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { useCartStore } from '@/stores/useCartStore'
import type { ProductListItem } from '@/types/product'

const props = defineProps<{
  product: ProductListItem
}>()

const router = useRouter()
const toast = useToast()
const cartStore = useCartStore()
const isAddingToCart = ref(false)

const stockSeverity = computed(() => {
  if (props.product.stockQuantity === 0) return 'danger'
  if (props.product.stockQuantity <= 20) return 'warn'
  return 'success'
})

const stockLabel = computed(() => {
  if (props.product.stockQuantity === 0) return 'Out of Stock'
  if (props.product.stockQuantity <= 20) return 'Low Stock'
  return 'In Stock'
})

const isOutOfStock = computed(() => props.product.stockQuantity === 0)

function navigateToProduct() {
  router.push(`/products/${props.product.id}`)
}

async function addToCart(event: Event) {
  event.stopPropagation()
  if (isAddingToCart.value) return
  isAddingToCart.value = true
  try {
    await cartStore.addItem({
      productId: props.product.id,
      productName: props.product.name,
      price: props.product.price,
      quantity: 1,
    })
    toast.add({
      severity: 'success',
      summary: 'Added to cart',
      detail: `${props.product.name} has been added to your cart.`,
      life: 3000,
      actionRoute: '/cart',
      actionLabel: 'Go to Cart',
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Failed to add',
      detail: 'Could not add item to cart. Please try again.',
      life: 5000,
    })
  } finally {
    isAddingToCart.value = false
  }
}
</script>

<template>
  <article
    class="product-card"
    :class="{ 'product-card--out-of-stock': isOutOfStock }"
    @click="navigateToProduct"
    role="link"
    tabindex="0"
    :aria-label="`${product.name}, $${product.price}`"
    @keydown.enter="navigateToProduct"
  >
    <div class="product-card__image-wrapper">
      <img
        :src="product.primaryImageUrl"
        :alt="product.name"
        class="product-card__image"
        loading="lazy"
      />
    </div>
    <div class="product-card__body">
      <h3 class="product-card__title">{{ product.name }}</h3>
      <p class="product-card__price">${{ product.price.toFixed(2) }}</p>
      <div class="product-card__rating">
        <Rating :modelValue="product.rating" :cancel="false" readonly />
      </div>
      <Tag
        :value="stockLabel"
        :severity="stockSeverity"
        :aria-label="`Stock status: ${stockLabel}`"
      />
    </div>
    <div v-if="!isOutOfStock" class="product-card__overlay">
      <Button
        label="Add to Cart"
        severity="secondary"
        text
        class="product-card__add-btn"
        @click="addToCart"
        aria-label="Add to cart"
      />
    </div>
  </article>
</template>

<style scoped>
.product-card {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  background: #ffffff;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 200ms;
}

.product-card:hover {
  box-shadow: var(--shadow-md);
}

.product-card--out-of-stock .product-card__image {
  filter: grayscale(0.6);
  opacity: 0.7;
}

.product-card__image-wrapper {
  aspect-ratio: 1 / 1;
  overflow: hidden;
  background: var(--color-gray-50);
}

.product-card__image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-card__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
}

.product-card__title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--color-gray-900);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.product-card__price {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-primary-600);
  margin: 0;
}

.product-card__rating {
  display: flex;
  align-items: center;
}

.product-card__overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 16px;
  background: linear-gradient(transparent, rgba(255, 255, 255, 0.95));
  opacity: 0;
  transition: opacity 200ms;
}

.product-card:hover .product-card__overlay {
  opacity: 1;
}

.product-card__add-btn {
  width: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .product-card,
  .product-card__overlay {
    transition: none;
  }
}
</style>
