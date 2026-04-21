<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { userManager } from '@/auth/keycloak'

const router = useRouter()

onMounted(async () => {
  try {
    await userManager.signinRedirectCallback()
    await router.replace('/admin/dashboard')
  } catch {
    await router.replace('/admin/unauthorized')
  }
})
</script>

<template>
  <div />
</template>
