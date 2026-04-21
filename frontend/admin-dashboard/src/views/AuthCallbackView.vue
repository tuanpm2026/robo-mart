<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { userManager } from '@/auth/keycloak'
import { useAdminAuthStore } from '@/stores/useAdminAuthStore'

const router = useRouter()

onMounted(async () => {
  try {
    await userManager.signinRedirectCallback()
    // Reset cached initPromise so the guard re-reads the freshly stored user
    const authStore = useAdminAuthStore()
    authStore.resetAuth()
    await authStore.initAuth()
    await router.replace('/admin/dashboard')
  } catch {
    await router.replace('/admin/unauthorized')
  }
})
</script>

<template>
  <div />
</template>
