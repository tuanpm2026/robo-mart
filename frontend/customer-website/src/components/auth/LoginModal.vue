<script setup lang="ts">
import { ref } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Divider from 'primevue/divider'
import Message from 'primevue/message'
import { useAuthStore } from '@/stores/useAuthStore'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const authStore = useAuthStore()
const email = ref('')
const emailError = ref('')
const isSubmitting = ref(false)

function validateEmail(): boolean {
  if (!email.value.trim()) {
    emailError.value = 'Email is required'
    return false
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value)) {
    emailError.value = 'Please enter a valid email'
    return false
  }
  emailError.value = ''
  return true
}

function onEmailBlur() {
  if (email.value.trim()) {
    validateEmail()
  }
}

async function handleSocialLogin(provider: string) {
  if (isSubmitting.value) return
  isSubmitting.value = true
  try {
    await authStore.login(provider)
  } catch {
    // Redirect errors are expected (page navigates away); real errors are caught by authStore
  } finally {
    isSubmitting.value = false
  }
}

async function handleEmailLogin() {
  if (!validateEmail()) return

  isSubmitting.value = true
  try {
    await authStore.login(undefined, email.value)
  } catch {
    // Redirect errors are expected
  } finally {
    isSubmitting.value = false
  }
}

async function handleRegister() {
  if (isSubmitting.value) return
  isSubmitting.value = true
  try {
    await authStore.register()
  } catch {
    // Redirect errors are expected
  } finally {
    isSubmitting.value = false
  }
}

function closeModal() {
  emit('update:visible', false)
  email.value = ''
  emailError.value = ''
  authStore.error = null
}
</script>

<template>
  <Dialog
    :visible="props.visible"
    modal
    :closable="true"
    header="Log in to RoboMart"
    class="login-modal"
    :style="{ width: '420px' }"
    @update:visible="closeModal"
  >
    <div class="login-modal__content">
      <Message v-if="authStore.error" severity="error" :closable="false" class="login-modal__error">
        {{ authStore.error }}
      </Message>

      <div class="login-modal__social">
        <Button
          label="Continue with Google"
          class="login-modal__social-btn login-modal__social-btn--google"
          outlined
          :disabled="isSubmitting"
          @click="handleSocialLogin('google')"
        >
          <template #icon>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
                fill="#4285F4"
              />
              <path
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                fill="#34A853"
              />
              <path
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
                fill="#FBBC05"
              />
              <path
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
                fill="#EA4335"
              />
            </svg>
          </template>
        </Button>

        <Button
          label="Continue with GitHub"
          class="login-modal__social-btn login-modal__social-btn--github"
          outlined
          :disabled="isSubmitting"
          @click="handleSocialLogin('github')"
        >
          <template #icon>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path
                d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2z"
              />
            </svg>
          </template>
        </Button>
      </div>

      <Divider align="center">
        <span class="login-modal__divider-text">or</span>
      </Divider>

      <form class="login-modal__form" @submit.prevent="handleEmailLogin">
        <div class="login-modal__field">
          <label for="login-email" class="login-modal__label">Email</label>
          <InputText
            id="login-email"
            v-model="email"
            type="email"
            placeholder="Enter your email"
            :disabled="isSubmitting"
            :invalid="!!emailError"
            fluid
            @blur="onEmailBlur"
          />
          <small v-if="emailError" class="login-modal__field-error">{{ emailError }}</small>
        </div>

        <Button
          type="submit"
          label="Continue with Email"
          :loading="isSubmitting"
          :disabled="isSubmitting"
          fluid
        />
      </form>

      <p class="login-modal__register">
        Don't have an account?
        <button
          type="button"
          class="login-modal__register-link"
          :disabled="isSubmitting"
          @click="handleRegister"
        >
          Register
        </button>
      </p>
    </div>
  </Dialog>
</template>

<style scoped>
.login-modal__content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-modal__error {
  margin: 0;
}

.login-modal__social {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.login-modal__social-btn {
  justify-content: center;
  gap: 8px;
}

.login-modal__social-btn :deep(.p-button-label) {
  flex: none;
}

.login-modal__divider-text {
  color: var(--color-gray-400);
  font-size: 13px;
}

.login-modal__form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-modal__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.login-modal__label {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-700);
}

.login-modal__field-error {
  color: var(--p-red-500, #ef4444);
  font-size: 12px;
}

.login-modal__register {
  text-align: center;
  font-size: 14px;
  color: var(--color-gray-500);
  margin: 4px 0 0;
}

.login-modal__register-link {
  background: none;
  border: none;
  color: var(--color-primary-600);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  font-size: inherit;
}

.login-modal__register-link:hover {
  text-decoration: underline;
}
</style>
