<script setup lang="ts">
import { watch, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import Stepper from 'primevue/stepper'
import StepList from 'primevue/steplist'
import Step from 'primevue/step'
import StepPanels from 'primevue/steppanels'
import StepPanel from 'primevue/steppanel'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import { useCartStore } from '@/stores/useCartStore'
import CheckoutOrderSummary from '@/components/checkout/CheckoutOrderSummary.vue'
import StepCartReview from '@/components/checkout/StepCartReview.vue'
import StepShippingAddress from '@/components/checkout/StepShippingAddress.vue'
import StepPayment from '@/components/checkout/StepPayment.vue'
import StepConfirm from '@/components/checkout/StepConfirm.vue'
import SagaProgressOverlay from '@/components/checkout/SagaProgressOverlay.vue'

const router = useRouter()
const checkoutStore = useCheckoutStore()
const cartStore = useCartStore()

onMounted(async () => {
  checkoutStore.$reset()
  if (cartStore.items.length === 0) {
    await cartStore.fetchCart()
  }
  if (cartStore.items.length === 0) {
    router.replace('/cart')
  }
})

// Give keyboard users a stable starting point after a successful step change.
watch(
  () => checkoutStore.currentStep,
  async () => {
    if (checkoutStore.isPlacingOrder) return
    await nextTick()
    const heading = [...document.querySelectorAll<HTMLElement>('.checkout h2')].find(
      (element) => element.getClientRects().length,
    )
    if (heading) {
      heading.tabIndex = -1
      heading.focus()
    }
  },
)

// Redirect to cart when inventory failure detected
watch(
  () => checkoutStore.error,
  (err) => {
    if (err?.type === 'INVENTORY_FAILED') {
      checkoutStore.$reset()
      router.push('/cart?error=out_of_stock')
    }
  },
)

// Redirect to /cart when cart becomes empty during checkout
watch(
  () => cartStore.items.length,
  (len) => {
    if (len === 0 && !checkoutStore.isPlacingOrder) {
      router.replace('/cart')
    }
  },
)
</script>

<template>
  <div class="checkout">
    <h1 class="checkout__title">Checkout</h1>

    <div v-if="checkoutStore.error?.type === 'UNKNOWN'" class="checkout__error" role="alert">
      {{ checkoutStore.error.message }}
    </div>
    <div class="checkout__layout">
      <div class="checkout__stepper">
        <Stepper :value="checkoutStore.currentStep" linear role="group" aria-label="Checkout">
          <StepList role="tablist" aria-label="Checkout steps">
            <Step
              v-for="(label, index) in ['Cart Review', 'Shipping', 'Payment', 'Confirm']"
              :key="label"
              :value="index + 1"
              as-child
              v-slot="{ a11yAttrs }"
            >
              <button
                v-bind="a11yAttrs.header"
                :id="`checkout-step-${index + 1}`"
                type="button"
                role="tab"
                :aria-controls="`checkout-panel-${index + 1}`"
                :aria-selected="checkoutStore.currentStep === index + 1"
                :tabindex="checkoutStore.currentStep === index + 1 ? 0 : -1"
                class="checkout-step-tab"
              >
                <span class="checkout-step-number" aria-hidden="true">{{ index + 1 }}</span
                ><span>{{ label }}</span>
              </button>
            </Step>
          </StepList>
          <StepPanels>
            <StepPanel
              :value="1"
              id="checkout-panel-1"
              aria-labelledby="checkout-step-1"
              aria-controls="checkout-step-1"
            >
              <StepCartReview @continue="checkoutStore.nextStep()" />
            </StepPanel>
            <StepPanel
              :value="2"
              id="checkout-panel-2"
              aria-labelledby="checkout-step-2"
              aria-controls="checkout-step-2"
            >
              <StepShippingAddress
                @continue="checkoutStore.nextStep()"
                @back="checkoutStore.prevStep()"
              />
            </StepPanel>
            <StepPanel
              :value="3"
              id="checkout-panel-3"
              aria-labelledby="checkout-step-3"
              aria-controls="checkout-step-3"
            >
              <StepPayment @continue="checkoutStore.nextStep()" @back="checkoutStore.prevStep()" />
            </StepPanel>
            <StepPanel
              :value="4"
              id="checkout-panel-4"
              aria-labelledby="checkout-step-4"
              aria-controls="checkout-step-4"
            >
              <StepConfirm @back="checkoutStore.prevStep()" />
            </StepPanel>
          </StepPanels>
        </Stepper>
      </div>

      <div class="checkout__sidebar">
        <CheckoutOrderSummary :items="cartStore.items" :total-price="cartStore.totalPrice" />
      </div>
    </div>

    <SagaProgressOverlay />
  </div>
</template>

<style scoped>
.checkout {
  padding: 24px 0;
}
.checkout__title {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-gray-900, #111827);
  margin: 0 0 24px;
}
.checkout__layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 32px;
  align-items: start;
}
@media (max-width: 768px) {
  .checkout__layout {
    grid-template-columns: 1fr;
  }
}

.checkout__layout {
  grid-template-columns: minmax(0, 1fr) 300px;
}
.checkout__stepper,
.checkout__sidebar {
  min-width: 0;
}
.checkout__stepper :deep(.p-steplist) {
  overflow-x: auto;
}
.checkout__stepper :deep(.p-step) {
  flex-shrink: 0;
}
@media (max-width: 1023px) {
  .checkout__layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 480px) {
  .checkout__stepper :deep(.p-steppanels) {
    padding: 16px 0;
  }
  .checkout__stepper :deep(.p-step-title) {
    font-size: 12px;
  }
}

.checkout-step-tab {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  background: transparent;
  color: var(--color-gray-600);
  border: 0;
  border-bottom: 3px solid var(--color-gray-200);
  padding: 8px 4px;
  font: inherit;
  font-size: 14px;
  min-height: 72px;
}
.checkout-step-tab[aria-selected='true'] {
  color: var(--color-primary-700);
  border-color: var(--color-primary-600);
  font-weight: 600;
}
.checkout-step-tab:disabled {
  opacity: 1;
}
.checkout-step-number {
  display: grid;
  place-items: center;
  border: 1px solid currentColor;
  border-radius: 50%;
  width: 28px;
  height: 28px;
}
@media (max-width: 480px) {
  .checkout-step-tab {
    font-size: 12px;
  }
}
.checkout__error {
  padding: 16px;
  margin-bottom: 16px;
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #b91c1c;
  border-radius: 6px;
}
</style>
