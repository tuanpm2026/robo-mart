<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/useUiStore'

const uiStore = useUiStore()

const showPartialBanner = computed(
  () => uiStore.degradationTier === 'partial' && !uiStore.isBannerDismissed,
)
const showMaintenanceOverlay = computed(() => uiStore.degradationTier === 'maintenance')
</script>

<template>
  <!-- Partial Degradation Banner (yellow, dismissible per session) -->
  <div
    v-if="showPartialBanner"
    class="degradation-banner degradation-banner--partial"
    role="alert"
    aria-live="assertive"
    aria-label="Service degradation notice"
  >
    <div class="degradation-banner__inner">
      <i class="pi pi-exclamation-triangle degradation-banner__icon" aria-hidden="true" />
      <span class="degradation-banner__message">
        Some features are temporarily limited. You can browse and add to cart — checkout will be
        available shortly.
      </span>
      <button
        class="degradation-banner__dismiss"
        type="button"
        aria-label="Dismiss service notice"
        @click="uiStore.dismissBanner()"
      >
        <i class="pi pi-times" aria-hidden="true" />
      </button>
    </div>
  </div>

  <!-- Maintenance Overlay (full-page, NOT dismissible) -->
  <div
    v-if="showMaintenanceOverlay"
    class="degradation-overlay"
    role="alertdialog"
    aria-live="assertive"
    aria-modal="true"
    aria-label="Maintenance notice"
  >
    <div class="degradation-overlay__card">
      <i class="pi pi-wrench degradation-overlay__icon" aria-hidden="true" />
      <h1 class="degradation-overlay__title">We'll be right back</h1>
      <p class="degradation-overlay__message">
        We're performing maintenance and will be back shortly.
      </p>
    </div>
  </div>
</template>

<style scoped>
/* Partial Banner */
.degradation-banner {
  width: 100%;
  z-index: 99;
}
.degradation-banner--partial {
  background-color: #fef9c3; /* yellow-100 */
  border-bottom: 1px solid #fde047; /* yellow-300 */
}
.degradation-banner__inner {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 10px 24px;
}
.degradation-banner__icon {
  color: #a16207; /* yellow-700 */
  font-size: 16px;
  flex-shrink: 0;
}
.degradation-banner__message {
  flex: 1;
  font-size: 14px;
  color: #713f12; /* yellow-900 */
}
.degradation-banner__dismiss {
  background: none;
  border: none;
  cursor: pointer;
  color: #a16207;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
}
.degradation-banner__dismiss:hover {
  background: #fde047;
}

/* Maintenance Overlay */
.degradation-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
}
.degradation-overlay__card {
  background: #ffffff;
  border-radius: 16px;
  padding: 48px;
  text-align: center;
  max-width: 480px;
  width: 90%;
}
.degradation-overlay__icon {
  font-size: 48px;
  color: var(--color-primary-600);
  margin-bottom: 24px;
}
.degradation-overlay__title {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 12px;
  color: var(--color-gray-900);
}
.degradation-overlay__message {
  font-size: 16px;
  color: var(--color-gray-600);
}
</style>
