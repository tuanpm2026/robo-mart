<script setup lang="ts">
import { useRoute, RouterView } from 'vue-router'
import AdminLayout from './layouts/AdminLayout.vue'

const route = useRoute()
</script>

<template>
  <RouterView v-if="route.meta.noLayout === true" />
  <AdminLayout v-else />
</template>
