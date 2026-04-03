<script setup lang="ts">
import { watch, onMounted } from 'vue'
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
  if (cartStore.items.length === 0) {
    await cartStore.fetchCart()
  }
  if (cartStore.items.length === 0) {
    router.replace('/cart')
  }
})

// Redirect to cart when inventory failure detected
watch(
  () => checkoutStore.error,
  (err) => {
    if (err?.type === 'INVENTORY_FAILED') {
      router.push('/cart?error=out_of_stock')
    }
  },
)
</script>

<template>
  <div class="checkout">
    <h1 class="checkout__title">Checkout</h1>

    <div class="checkout__layout">
      <div class="checkout__stepper">
        <Stepper :value="checkoutStore.currentStep" linear>
          <StepList>
            <Step :value="1">Cart Review</Step>
            <Step :value="2">Shipping</Step>
            <Step :value="3">Payment</Step>
            <Step :value="4">Confirm</Step>
          </StepList>
          <StepPanels>
            <StepPanel :value="1">
              <StepCartReview @continue="checkoutStore.nextStep()" />
            </StepPanel>
            <StepPanel :value="2">
              <StepShippingAddress
                @continue="checkoutStore.nextStep()"
                @back="checkoutStore.prevStep()"
              />
            </StepPanel>
            <StepPanel :value="3">
              <StepPayment
                @continue="checkoutStore.nextStep()"
                @back="checkoutStore.prevStep()"
              />
            </StepPanel>
            <StepPanel :value="4">
              <StepConfirm @back="checkoutStore.prevStep()" />
            </StepPanel>
          </StepPanels>
        </Stepper>
      </div>

      <div class="checkout__sidebar">
        <CheckoutOrderSummary
          :items="cartStore.items"
          :total-price="cartStore.totalPrice"
        />
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
</style>
