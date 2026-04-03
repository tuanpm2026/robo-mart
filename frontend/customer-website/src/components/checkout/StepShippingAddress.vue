<script setup lang="ts">
import { useForm, useField } from 'vee-validate'
import { object, string } from 'yup'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
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

const { handleSubmit } = useForm<ShippingFormData>({
  validationSchema: schema,
  initialValues: checkoutStore.shippingData ?? {
    fullName: '', street: '', city: '', state: '', postalCode: '', country: 'US',
  },
})

const { value: fullName, errorMessage: fullNameError } = useField<string>('fullName')
const { value: street, errorMessage: streetError } = useField<string>('street')
const { value: city, errorMessage: cityError } = useField<string>('city')
const { value: state, errorMessage: stateError } = useField<string>('state')
const { value: postalCode, errorMessage: postalCodeError } = useField<string>('postalCode')
const { value: country, errorMessage: countryError } = useField<string>('country')

const onSubmit = handleSubmit((values) => {
  checkoutStore.setShippingData(values)
  emit('continue')
})
</script>

<template>
  <div class="ssa">
    <h2 class="ssa__heading">Shipping Address</h2>

    <form class="ssa__form" novalidate @submit.prevent="onSubmit">
      <div class="ssa__field">
        <label for="fullName" class="ssa__label">Full Name</label>
        <InputText
          id="fullName"
          v-model="fullName"
          placeholder="Jane Doe"
          :class="{ 'p-invalid': fullNameError }"
          class="ssa__input"
        />
        <small v-if="fullNameError" class="p-error">{{ fullNameError }}</small>
      </div>

      <div class="ssa__field">
        <label for="street" class="ssa__label">Street Address</label>
        <InputText
          id="street"
          v-model="street"
          placeholder="123 Main Street"
          :class="{ 'p-invalid': streetError }"
          class="ssa__input"
        />
        <small v-if="streetError" class="p-error">{{ streetError }}</small>
      </div>

      <div class="ssa__row">
        <div class="ssa__field">
          <label for="city" class="ssa__label">City</label>
          <InputText
            id="city"
            v-model="city"
            placeholder="San Francisco"
            :class="{ 'p-invalid': cityError }"
            class="ssa__input"
          />
          <small v-if="cityError" class="p-error">{{ cityError }}</small>
        </div>

        <div class="ssa__field">
          <label for="state" class="ssa__label">State / Province</label>
          <InputText
            id="state"
            v-model="state"
            placeholder="CA"
            :class="{ 'p-invalid': stateError }"
            class="ssa__input"
          />
          <small v-if="stateError" class="p-error">{{ stateError }}</small>
        </div>
      </div>

      <div class="ssa__row">
        <div class="ssa__field">
          <label for="postalCode" class="ssa__label">Postal Code</label>
          <InputText
            id="postalCode"
            v-model="postalCode"
            placeholder="94105"
            :class="{ 'p-invalid': postalCodeError }"
            class="ssa__input"
          />
          <small v-if="postalCodeError" class="p-error">{{ postalCodeError }}</small>
        </div>

        <div class="ssa__field">
          <label for="country" class="ssa__label">Country</label>
          <InputText
            id="country"
            v-model="country"
            placeholder="US"
            :class="{ 'p-invalid': countryError }"
            class="ssa__input"
          />
          <small v-if="countryError" class="p-error">{{ countryError }}</small>
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
</style>
