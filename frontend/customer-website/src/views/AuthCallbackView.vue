<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/useAuthStore'

const router = useRouter()
const authStore = useAuthStore()
const callbackError = ref<string | null>(null)

onMounted(async () => {
  try {
    const redirectPath = await authStore.handleCallback()
    router.replace(redirectPath)
  } catch (err) {
    callbackError.value = err instanceof Error ? err.message : 'Authentication failed'
  }
})
</script>

<template>
  <div class="auth-callback">
    <div v-if="callbackError" class="auth-callback__error">
      <p>Authentication failed: {{ callbackError }}</p>
      <RouterLink to="/">Return to home</RouterLink>
    </div>
    <div v-else class="auth-callback__loading">
      <p>Completing login...</p>
    </div>
  </div>
</template>

<style scoped>
.auth-callback {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 60vh;
}

.auth-callback__loading,
.auth-callback__error {
  text-align: center;
  color: var(--color-gray-600);
}

.auth-callback__error {
  color: var(--p-red-500, #ef4444);
}

.auth-callback__error a {
  display: inline-block;
  margin-top: 8px;
  color: var(--color-primary-600);
}
</style>
