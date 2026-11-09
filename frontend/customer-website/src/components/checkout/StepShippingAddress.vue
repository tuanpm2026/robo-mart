<script setup lang="ts">
import { ref } from 'vue'
import { useForm, useField } from 'vee-validate'
import { object, string } from 'yup'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import FormErrorSummary from './FormErrorSummary.vue'
import { useCheckoutStore } from '@/stores/useCheckoutStore'
import type { ShippingFormData } from '@/types/checkout'

const emit = defineEmits<{ continue: []; back: [] }>()
const checkoutStore = useCheckoutStore()

const schema = object({
  fullName: string().required('Full name is required').min(2, 'Full name is too short'),
  street: string().required('Street address is required'),
  city: string().required('City is required'),
  state: string().required('State / Province is required'),
  postalCode: string()
    .required('Postal code is required')
    .matches(/^\d{5}(-\d{4})?$/, 'Enter a valid postal code (e.g. 12345)'),
  country: string().required('Country is required'),
})

const submitted = ref(false)
const errorSummary = ref<InstanceType<typeof FormErrorSummary> | null>(null)
const { handleSubmit, errors } = useForm<ShippingFormData>({
  validationSchema: schema,
  validateOnMount: false,
  initialValues: checkoutStore.shippingData ?? {
    fullName: '',
    street: '',
    city: '',
    state: '',
    postalCode: '',
    country: 'US',
  },
})

const { value: fullName, errorMessage: fullNameError } = useField<string>('fullName')
const { value: street, errorMessage: streetError } = useField<string>('street')
const { value: city, errorMessage: cityError } = useField<string>('city')
const { value: state, errorMessage: stateError } = useField<string>('state')
const { value: postalCode, errorMessage: postalCodeError } = useField<string>('postalCode')
const { value: country, errorMessage: countryError } = useField<string>('country')

const onSubmit = handleSubmit(
  (values) => {
    checkoutStore.setShippingData(values)
    emit('continue')
  },
  () => {
    submitted.value = true
    errorSummary.value?.focus()
  },
)
</script>

<template>
  <div class="ssa">
    <h2 class="ssa__heading">Shipping Address</h2>

    <FormErrorSummary ref="errorSummary" :errors="submitted ? errors : {}" />
    <form class="ssa__form" novalidate @submit.prevent="onSubmit">
      <div class="ssa__field">
        <label for="fullName" class="ssa__label">Full Name</label>
        <InputText
          id="fullName"
          autocomplete="name"
          :aria-invalid="!!fullNameError"
          :aria-describedby="fullNameError ? 'fullName-error' : undefined"
          v-model="fullName"
          placeholder="Jane Doe"
          :class="{ 'p-invalid': fullNameError }"
          class="ssa__input"
        />
        <small v-if="fullNameError" id="fullName-error" class="field-error">{{
          fullNameError
        }}</small>
      </div>

      <div class="ssa__field">
        <label for="street" class="ssa__label">Street Address</label>
        <InputText
          id="street"
          autocomplete="address-line1"
          :aria-invalid="!!streetError"
          :aria-describedby="streetError ? 'street-error' : undefined"
          v-model="street"
          placeholder="123 Main Street"
          :class="{ 'p-invalid': streetError }"
          class="ssa__input"
        />
        <small v-if="streetError" id="street-error" class="field-error">{{ streetError }}</small>
      </div>

      <div class="ssa__row">
        <div class="ssa__field">
          <label for="city" class="ssa__label">City</label>
          <InputText
            id="city"
            autocomplete="address-level2"
            :aria-invalid="!!cityError"
            :aria-describedby="cityError ? 'city-error' : undefined"
            v-model="city"
            placeholder="San Francisco"
            :class="{ 'p-invalid': cityError }"
            class="ssa__input"
          />
          <small v-if="cityError" id="city-error" class="field-error">{{ cityError }}</small>
        </div>

        <div class="ssa__field">
          <label for="state" class="ssa__label">State / Province</label>
          <InputText
            id="state"
            autocomplete="address-level1"
            :aria-invalid="!!stateError"
            :aria-describedby="stateError ? 'state-error' : undefined"
            v-model="state"
            placeholder="CA"
            :class="{ 'p-invalid': stateError }"
            class="ssa__input"
          />
          <small v-if="stateError" id="state-error" class="field-error">{{ stateError }}</small>
        </div>
      </div>

      <div class="ssa__row">
        <div class="ssa__field">
          <label for="postalCode" class="ssa__label">Postal Code</label>
          <InputText
            id="postalCode"
            autocomplete="postal-code"
            :aria-invalid="!!postalCodeError"
            :aria-describedby="postalCodeError ? 'postalCode-error' : undefined"
            inputmode="numeric"
            v-model="postalCode"
            placeholder="94105"
            :class="{ 'p-invalid': postalCodeError }"
            class="ssa__input"
          />
          <small v-if="postalCodeError" id="postalCode-error" class="field-error">{{
            postalCodeError
          }}</small>
        </div>

        <div class="ssa__field">
          <label for="country" class="ssa__label">Country</label>
          <InputText
            id="country"
            autocomplete="country"
            :aria-invalid="!!countryError"
            :aria-describedby="countryError ? 'country-error' : undefined"
            v-model="country"
            placeholder="US"
            :class="{ 'p-invalid': countryError }"
            class="ssa__input"
          />
          <small v-if="countryError" id="country-error" class="field-error">{{
            countryError
          }}</small>
        </div>
      </div>

      <div class="ssa__actions">
        <Button label="← Back" severity="secondary" outlined type="button" @click="emit('back')" />
        <Button label="Continue to Payment →" severity="primary" type="submit" />
      </div>
    </form>
  </div>
</template>

<style scoped>
.ssa__heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 20px;
}
.ssa__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.ssa__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.ssa__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ssa__label {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-700, #374151);
}
.ssa__input {
  width: 100%;
}
.ssa__actions {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  gap: 12px;
}
.field-error {
  color: #b91c1c;
  font-size: 14px;
}
.ssa__field {
  min-width: 0;
}
.ssa__actions {
  flex-wrap: wrap;
}
@media (max-width: 480px) {
  .ssa__row {
    grid-template-columns: minmax(0, 1fr);
  }
  .ssa__actions > * {
    width: 100%;
  }
  .ssa__input {
    font-size: 16px;
  }
}
</style>
