<script setup lang="ts">
import AdminDataTableDemo from '@/components/AdminDataTableDemo.vue'
</script>

<template>
  <div>
    <h1 class="admin-page-title">Dashboard</h1>
    <AdminDataTableDemo />
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
