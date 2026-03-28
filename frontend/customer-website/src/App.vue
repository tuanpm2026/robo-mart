<script setup lang="ts">
import DefaultLayout from './layouts/DefaultLayout.vue'
import Toast from 'primevue/toast'
</script>

<template>
  <a href="#main-content" class="skip-to-main">Skip to main content</a>
  <Toast position="bottom-right" :max="3" />
  <DefaultLayout />
</template>
