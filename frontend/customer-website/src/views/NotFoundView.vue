<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found" role="status">
    <h1 class="not-found__title">Page not found</h1>
    <p class="not-found__description">
      The page you're looking for doesn't exist or has been moved.
    </p>
    <button class="not-found__cta" type="button" @click="router.push('/')">Go Home</button>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  text-align: center;
  gap: 12px;
}

.not-found__title {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--color-gray-900);
}

.not-found__description {
  font-size: 16px;
  color: var(--color-gray-600);
  max-width: 400px;
}

.not-found__cta {
  margin-top: 8px;
  padding: 10px 24px;
  min-height: 40px;
  font-size: 16px;
  font-weight: 600;
  color: #ffffff;
  background: var(--color-primary-600);
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 200ms;
}

.not-found__cta:hover {
  background: var(--color-primary-700);
}

@media (prefers-reduced-motion: reduce) {
  .not-found__cta {
    transition: none;
  }
}
</style>
