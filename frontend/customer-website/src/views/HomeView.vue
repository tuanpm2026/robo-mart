<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import ProductCard from '@/components/product/ProductCard.vue'
import ProductCardSkeleton from '@/components/product/ProductCardSkeleton.vue'
import { useProductStore } from '@/stores/useProductStore'

const productStore = useProductStore()
const route = useRoute()

function getCategoryId(): number | undefined {
  const id = route.query.categoryId
  return id ? Number(id) : undefined
}

onMounted(() => {
  productStore.fetchProducts(getCategoryId())
})

watch(
  () => route.query.categoryId,
  () => {
    productStore.fetchProducts(getCategoryId())
  },
)

function loadMore() {
  productStore.loadMoreProducts(getCategoryId())
}
</script>

<template>
  <div class="home">
    <h1 class="home__title">Discover Products</h1>

    <div v-if="productStore.isLoading && productStore.products.length === 0" class="home__grid">
      <ProductCardSkeleton v-for="n in 8" :key="n" />
    </div>

    <div v-else-if="productStore.error" class="home__error" role="alert">
      <p>{{ productStore.error }}</p>
      <Button label="Try Again" severity="secondary" @click="productStore.fetchProducts(getCategoryId())" />
    </div>

    <template v-else>
      <div class="home__grid">
        <ProductCard
          v-for="product in productStore.products"
          :key="product.id"
          :product="product"
        />
      </div>

      <div v-if="productStore.hasMoreProducts" class="home__load-more">
        <Button
          label="Load more"
          severity="secondary"
          outlined
          :loading="productStore.isLoading"
          @click="loadMore"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.home {
  padding: 24px 0;
}

.home__title {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--color-gray-900);
  margin-bottom: 24px;
}

.home__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;
}

@media (max-width: 1279px) {
  .home__grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

.home__load-more {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

.home__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 48px 0;
  text-align: center;
  color: var(--color-gray-600);
}
</style>
