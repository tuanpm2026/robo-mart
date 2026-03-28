<script setup lang="ts">
import Skeleton from 'primevue/skeleton'
</script>

<template>
  <div class="product-card-skeleton" aria-hidden="true">
    <Skeleton class="product-card-skeleton__image" />
    <div class="product-card-skeleton__body">
      <Skeleton width="80%" height="1.4rem" />
      <Skeleton width="40%" height="1.2rem" />
      <Skeleton width="60%" height="1rem" />
      <Skeleton width="30%" height="1.5rem" borderRadius="16px" />
    </div>
  </div>
</template>

<style scoped>
.product-card-skeleton {
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  overflow: hidden;
}

.product-card-skeleton__image {
  aspect-ratio: 1 / 1;
  width: 100% !important;
  height: auto !important;
}

.product-card-skeleton__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
}
</style>
