<script setup lang="ts">
import { useCheckoutStore } from '@/stores/useCheckoutStore'

const checkoutStore = useCheckoutStore()
</script>

<template>
  <Transition name="fade">
    <div
      v-if="checkoutStore.isPlacingOrder"
      class="spo"
      role="status"
      aria-live="polite"
      aria-label="Processing your order"
    >
      <div class="spo__content">
        <div class="spo__spinner" aria-hidden="true">
          <svg viewBox="0 0 50 50" class="spo__svg">
            <circle class="spo__circle" cx="25" cy="25" r="20" fill="none" stroke-width="4" />
          </svg>
        </div>
        <Transition name="msg" mode="out-in">
          <p :key="checkoutStore.sagaMessage" class="spo__message">
            {{ checkoutStore.sagaMessage }}
          </p>
        </Transition>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.spo {
  position: fixed;
  inset: 0;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.spo__content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}
.spo__spinner { width: 52px; height: 52px; }
.spo__svg {
  width: 100%;
  height: 100%;
  animation: spo-rotate 1.2s linear infinite;
}
.spo__circle {
  stroke: var(--p-primary-color, #2563eb);
  stroke-dasharray: 100;
  stroke-dashoffset: 25;
  stroke-linecap: round;
}
.spo__message {
  font-size: 18px;
  font-weight: 500;
  color: var(--color-gray-800, #1f2937);
  margin: 0;
  text-align: center;
}
@keyframes spo-rotate { to { transform: rotate(360deg); } }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
.msg-enter-active, .msg-leave-active { transition: opacity 0.25s, transform 0.25s; }
.msg-enter-from { opacity: 0; transform: translateY(6px); }
.msg-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
