<script setup lang="ts">
import { computed } from 'vue'
import { useForm, useField } from 'vee-validate'
import { object, string } from 'yup'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import type { PaymentFormData } from '@/types/checkout'

const emit = defineEmits<{ continue: []; back: [] }>()
const checkoutStore = useCheckoutStore()

const schema = object({
  cardholderName: string().required('Cardholder name is required'),
  cardNumber: string()
    .required('Card number is required')
    .test('card-number', 'Enter a valid 16-digit card number', (val) => {
      if (!val) return false
      return /^\d{16}$/.test(val.replace(/\s/g, ''))
    }),
  expiry: string()
    .required('Expiry date is required')
    .matches(/^(0[1-9]|1[0-2])\/\d{2}$/, 'Use MM/YY format')
    .test('future-date', 'This card has expired', (value) => {
      if (!value) return true
      const parts = value.split('/')
      if (parts.length !== 2) return false
      const month = parseInt(parts[0], 10)
      const year = parseInt(parts[1], 10)
      const expiry = new Date(2000 + year, month - 1, 1)
      const now = new Date()
      return expiry >= new Date(now.getFullYear(), now.getMonth(), 1)
    }),
  cvv: string().required('CVV is required').matches(/^\d{3,4}$/, 'Enter a valid CVV'),
})

const { handleSubmit } = useForm<PaymentFormData>({
  validationSchema: schema,
  validateOnMount: false,
  initialValues: checkoutStore.paymentData ?? {
    cardholderName: '', cardNumber: '', expiry: '', cvv: '',
  },
})

// cardholderName validates on blur only (AC5)
const {
  value: cardholderName,
  errorMessage: cardholderNameError,
  handleBlur: blurCardholderName,
} = useField<string>('cardholderName', undefined, { validateOnValueUpdate: false })
const { value: cardNumber, errorMessage: cardNumberError } = useField<string>('cardNumber')
const { value: expiry, errorMessage: expiryError } = useField<string>('expiry')
const { value: cvv, errorMessage: cvvError } = useField<string>('cvv')

// Format card number display: insert space every 4 digits
const formattedCardNumber = computed(() => {
  if (!cardNumber.value) return ''
  return cardNumber.value
    .replace(/\s/g, '')
    .replace(/(.{4})/g, '$1 ')
    .trim()
})

function onCardNumberInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value.replace(/\s/g, '').replace(/\D/g, '')
  cardNumber.value = raw.slice(0, 16)
}

function onExpiryInput(event: Event) {
  let val = (event.target as HTMLInputElement).value.replace(/\D/g, '')
  if (val.length >= 2) {
    val = val.slice(0, 2) + '/' + val.slice(2, 4)
  }
  expiry.value = val
}

const onSubmit = handleSubmit((values) => {
  checkoutStore.setPaymentData(values)
  emit('continue')
})

const paymentError = computed(() =>
  checkoutStore.error?.type === 'PAYMENT_FAILED' ? checkoutStore.error.message : null
)
</script>

<template>
  <div class="sp">
    <h2 class="sp__heading">Payment</h2>

    <div v-if="paymentError" class="sp__error-banner" role="alert">
      <span>⚠️ {{ paymentError }}</span>
    </div>

    <p class="sp__notice">This is a demo checkout. No real payment is processed.</p>

    <form class="sp__form" novalidate @submit.prevent="onSubmit">
      <div class="sp__field">
        <label for="cardholderName" class="sp__label">Cardholder Name</label>
        <InputText
          id="cardholderName"
          v-model="cardholderName"
          placeholder="Jane Doe"
          :class="{ 'p-invalid': cardholderNameError }"
          class="sp__input"
          @blur="blurCardholderName"
        />
        <small v-if="cardholderNameError" class="p-error">{{ cardholderNameError }}</small>
      </div>

      <div class="sp__field">
        <label for="cardNumber" class="sp__label">Card Number</label>
        <InputText
          id="cardNumber"
          :value="formattedCardNumber"
          placeholder="1234 5678 9012 3456"
          maxlength="19"
          :class="{ 'p-invalid': cardNumberError }"
          class="sp__input sp__card-number"
          @input="onCardNumberInput"
        />
        <small v-if="cardNumberError" class="p-error">{{ cardNumberError }}</small>
        <small class="sp__hint">Use 4242 4242 4242 4242 for a successful demo payment</small>
      </div>

      <div class="sp__row">
        <div class="sp__field">
          <label for="expiry" class="sp__label">Expiry Date</label>
          <InputText
            id="expiry"
            :value="expiry"
            placeholder="MM/YY"
            maxlength="5"
            :class="{ 'p-invalid': expiryError }"
            class="sp__input"
            @input="onExpiryInput"
          />
          <small v-if="expiryError" class="p-error">{{ expiryError }}</small>
        </div>

        <div class="sp__field">
          <label for="cvv" class="sp__label">CVV</label>
          <InputText
            id="cvv"
            v-model="cvv"
            placeholder="123"
            maxlength="4"
            type="password"
            :class="{ 'p-invalid': cvvError }"
            class="sp__input"
          />
          <small v-if="cvvError" class="p-error">{{ cvvError }}</small>
        </div>
      </div>

      <div class="sp__actions">
        <Button label="← Back" severity="secondary" outlined type="button" @click="emit('back')" />
        <Button label="Continue to Confirm →" severity="primary" type="submit" />
      </div>
    </form>
  </div>
</template>

<style scoped>
.sp__heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 20px;
}
.sp__error-banner {
  padding: 12px 16px;
  background: #fef2f2;
  border: 1px solid #fca5a5;
  border-radius: 6px;
  color: #b91c1c;
  font-size: 14px;
  margin-bottom: 16px;
}
.sp__notice {
  font-size: 12px;
  color: var(--color-gray-500, #6b7280);
  margin: 0 0 16px;
}
.sp__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.sp__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.sp__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sp__label {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-700, #374151);
}
.sp__input {
  width: 100%;
}
.sp__card-number {
  font-family: 'Courier New', monospace;
  letter-spacing: 2px;
}
.sp__hint {
  font-size: 11px;
  color: var(--color-gray-400, #9ca3af);
}
.sp__actions {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  gap: 12px;
}
</style>
