<script setup lang="ts">
import { EmptyState } from '@robo-mart/shared'
</script>

<template>
  <div>
    <h1 class="admin-page-title">Orders</h1>
    <EmptyState variant="admin-orders" />
  </div>
</template>

<style scoped>
.admin-page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin-bottom: 16px;
}
</style>
