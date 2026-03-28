<script setup lang="ts">
import { onMounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import Galleria from 'primevue/galleria'
import Rating from 'primevue/rating'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Breadcrumb from 'primevue/breadcrumb'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { EmptyState } from '@robo-mart/shared'
import { useProductStore } from '@/stores/useProductStore'

const route = useRoute()
const productStore = useProductStore()
const toast = useToast()

const productId = computed(() => Number(route.params.id))

const product = computed(() => productStore.selectedProduct)

const stockSeverity = computed(() => {
  if (!product.value) return 'info'
  if (product.value.stockQuantity === 0) return 'danger'
  if (product.value.stockQuantity <= 20) return 'warn'
  return 'success'
})

const stockLabel = computed(() => {
  if (!product.value) return ''
  if (product.value.stockQuantity === 0) return 'Out of Stock'
  if (product.value.stockQuantity <= 20) return `Low Stock — ${product.value.stockQuantity} left`
  return 'In Stock'
})

const breadcrumbItems = computed(() => {
  if (!product.value) return []
  return [
    { label: product.value.category.name },
    { label: product.value.name },
  ]
})

const breadcrumbHome = { icon: 'pi pi-home', to: '/' }

const galleriaImages = computed(() => {
  if (!product.value) return []
  return product.value.images
    .slice()
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((img) => ({
      itemImageSrc: img.imageUrl,
      thumbnailImageSrc: img.imageUrl,
      alt: img.altText || product.value!.name,
    }))
})

const galleriaResponsiveOptions = [
  { breakpoint: '1300px', numVisible: 4 },
  { breakpoint: '575px', numVisible: 3 },
]

onMounted(() => {
  if (Number.isNaN(productId.value)) {
    productStore.error = 'Invalid product ID'
    return
  }
  productStore.fetchProductDetail(productId.value)
})

watch(productId, (newId) => {
  if (Number.isNaN(newId)) {
    productStore.error = 'Invalid product ID'
    return
  }
  productStore.fetchProductDetail(newId)
})

function addToCart() {
  toast.add({
    severity: 'info',
    summary: 'Cart coming soon',
    detail: 'Shopping cart will be available in a future update.',
    life: 3000,
  })
}
</script>

<template>
  <div class="product-detail">
    <!-- Loading skeleton -->
    <div v-if="productStore.isLoading && !product" class="product-detail__skeleton">
      <Skeleton width="100%" height="400px" />
      <div class="product-detail__skeleton-info">
        <Skeleton width="60%" height="2rem" />
        <Skeleton width="30%" height="1.5rem" />
        <Skeleton width="40%" height="1rem" />
        <Skeleton width="50%" height="1rem" />
        <Skeleton width="100%" height="6rem" />
      </div>
    </div>

    <!-- Not found -->
    <EmptyState
      v-else-if="!product && !productStore.isLoading && productStore.error"
      variant="generic"
      title="Product not found"
      description="The product you're looking for doesn't exist or has been removed."
      ctaLabel="Browse Products"
      @action="$router.push('/')"
    />

    <!-- Product content -->
    <template v-else-if="product">
      <Breadcrumb :model="breadcrumbItems" :home="breadcrumbHome" class="product-detail__breadcrumb" />

      <div class="product-detail__content">
        <div class="product-detail__gallery">
          <Galleria
            v-if="galleriaImages.length > 0"
            :value="galleriaImages"
            :numVisible="4"
            :responsiveOptions="galleriaResponsiveOptions"
            :showThumbnails="galleriaImages.length > 1"
            :showIndicators="false"
            containerClass="product-detail__galleria"
          >
            <template #item="slotProps">
              <img
                :src="slotProps.item.itemImageSrc"
                :alt="slotProps.item.alt"
                class="product-detail__main-image"
              />
            </template>
            <template #thumbnail="slotProps">
              <img
                :src="slotProps.item.thumbnailImageSrc"
                :alt="slotProps.item.alt"
                class="product-detail__thumb-image"
              />
            </template>
          </Galleria>
          <div v-else class="product-detail__no-image">
            <span>No image available</span>
          </div>
        </div>

        <div class="product-detail__info">
          <h1 class="product-detail__title">{{ product.name }}</h1>

          <p class="product-detail__price">${{ product.price.toFixed(2) }}</p>

          <Tag
            :value="stockLabel"
            :severity="stockSeverity"
            class="product-detail__stock"
            :aria-label="`Stock status: ${stockLabel}`"
          />

          <div class="product-detail__rating">
            <Rating :modelValue="product.rating" :cancel="false" readonly />
            <span class="product-detail__rating-text">{{ product.rating?.toFixed(1) }}</span>
          </div>

          <p v-if="product.brand" class="product-detail__brand">
            Brand: <strong>{{ product.brand }}</strong>
          </p>

          <Button
            v-if="product.stockQuantity > 0"
            label="Add to Cart"
            icon="pi pi-shopping-cart"
            @click="addToCart"
            class="product-detail__add-btn"
          />

          <div v-if="product.description" class="product-detail__description">
            <h2>Description</h2>
            <p>{{ product.description }}</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.product-detail {
  padding: 16px 0;
}

.product-detail__breadcrumb {
  margin-bottom: 24px;
}

.product-detail__content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: start;
}

.product-detail__gallery {
  position: sticky;
  top: 100px;
}

.product-detail__main-image {
  width: 100%;
  max-height: 500px;
  object-fit: contain;
  border-radius: 8px;
}

.product-detail__thumb-image {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 4px;
}

.product-detail__no-image {
  aspect-ratio: 1 / 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-gray-50);
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  color: var(--color-gray-400);
  font-size: 14px;
}

.product-detail__info {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.product-detail__title {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--color-gray-900);
  margin: 0;
}

.product-detail__price {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-primary-600);
  margin: 0;
}

.product-detail__stock {
  align-self: flex-start;
}

.product-detail__rating {
  display: flex;
  align-items: center;
  gap: 8px;
}

.product-detail__rating-text {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-600);
}

.product-detail__brand {
  font-size: 14px;
  color: var(--color-gray-600);
  margin: 0;
}

.product-detail__add-btn {
  align-self: flex-start;
  margin-top: 8px;
}

.product-detail__description {
  margin-top: 16px;
}

.product-detail__description h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0 0 8px;
}

.product-detail__description p {
  font-size: 16px;
  line-height: 1.6;
  color: var(--color-gray-600);
  margin: 0;
}

.product-detail__skeleton {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
}

.product-detail__skeleton-info {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
