<script setup lang="ts">
import { useRouter } from 'vue-router'
import DefaultLayout from './layouts/DefaultLayout.vue'
import Toast from 'primevue/toast'
import Button from 'primevue/button'

const router = useRouter()
</script>

<template>
  <a href="#main-content" class="skip-to-main">Skip to main content</a>
  <Toast position="bottom-right" :max="3">
    <template #message="{ message }">
      <div class="toast-msg">
        <i
          :class="[
            'toast-msg__icon',
            message.severity === 'success' ? 'pi pi-check-circle' : '',
            message.severity === 'error' ? 'pi pi-times-circle' : '',
            message.severity === 'warn' ? 'pi pi-exclamation-triangle' : '',
            message.severity === 'info' ? 'pi pi-info-circle' : '',
          ]"
        />
        <div class="toast-msg__text">
          <span class="toast-msg__summary">{{ message.summary }}</span>
          <p class="toast-msg__detail">{{ message.detail }}</p>
        </div>
        <Button
          v-if="message.actionRoute"
          :label="message.actionLabel"
          size="small"
          text
          class="toast-msg__action"
          @click="router.push(message.actionRoute)"
        />
      </div>
    </template>
  </Toast>
  <DefaultLayout />
</template>

<style>
.toast-msg {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
}

.toast-msg__icon {
  font-size: 1.25rem;
  flex-shrink: 0;
  margin-top: 2px;
}

.toast-msg__text {
  flex: 1;
  min-width: 0;
}

.toast-msg__summary {
  font-weight: 600;
  font-size: 14px;
}

.toast-msg__detail {
  font-size: 13px;
  margin: 4px 0 0;
  opacity: 0.85;
}

.toast-msg__action {
  flex-shrink: 0;
  align-self: center;
}
</style>
