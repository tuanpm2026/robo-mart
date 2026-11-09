<script setup lang="ts">
import { ref, nextTick } from 'vue'
import Dialog from 'primevue/dialog'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
const checkoutStore = useCheckoutStore()
const processingStatus = ref<HTMLElement | null>(null)
async function focusProcessing() {
  await nextTick()
  processingStatus.value?.focus()
}
function focusResult() {
  const visible = (selector: string) =>
    [...document.querySelectorAll<HTMLElement>(selector)].find(
      (element) => element.getClientRects().length,
    )
  const target = visible('.checkout [role="alert"]') ?? visible('.checkout h2, .ocv h1')
  if (target) {
    target.tabIndex = -1
    target.focus()
  }
}
</script>
<template>
  <Dialog
    block-scroll
    :visible="checkoutStore.isPlacingOrder"
    modal
    :closable="false"
    :close-on-escape="false"
    header="Processing your order"
    @after-hide="focusResult"
    @show="focusProcessing"
    :style="{ width: 'min(420px, calc(100vw - 32px))' }"
  >
    <div ref="processingStatus" class="spo" role="status" aria-live="polite" tabindex="0">
      <i class="pi pi-spin pi-spinner" aria-hidden="true" />
      <p>Submitting your order. Please wait for the result before trying again.</p>
    </div>
  </Dialog>
</template>
<style scoped>
.spo {
  text-align: center;
  color: var(--color-gray-800);
}
.spo .pi {
  font-size: 32px;
}
@media (prefers-reduced-motion: reduce) {
  .pi-spin {
    animation: none;
  }
}
</style>
