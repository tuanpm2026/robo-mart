import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import StepShippingAddress from './StepShippingAddress.vue'
import StepPayment from './StepPayment.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('Checkout accessible validation', () => {
  it.each([
    [StepShippingAddress, ['fullName', 'street', 'city', 'state', 'postalCode']],
    [StepPayment, ['cardholderName', 'cardNumber', 'expiry', 'cvv']],
  ] as const)(
    'associates errors and focuses a linked summary after invalid submission',
    async (component, fields) => {
      const wrapper = mount(component, {
        attachTo: document.body,
        global: { plugins: [createPinia(), PrimeVue] },
      })
      wrappers.push(wrapper)
      await wrapper.get('form').trigger('submit')
      await vi.waitFor(() => expect(wrapper.find('.form-error-summary').exists()).toBe(true))
      await vi.waitFor(() =>
        expect(document.activeElement).toBe(wrapper.get('.form-error-summary').element),
      )
      for (const field of fields) {
        const input = wrapper.get('input#' + field)
        expect(wrapper.get(`label[for="${field}"]`).text()).toBeTruthy()
        expect(input.attributes('aria-invalid')).toBe('true')
        const error = wrapper.get('#' + input.attributes('aria-describedby'))
        expect(error.text()).toBeTruthy()
        expect(input.attributes('autocomplete')).toBeTruthy()
      }
      await wrapper.get(`.form-error-summary a[href="#${fields[0]}"]`).trigger('click')
      expect(document.activeElement).toBe(wrapper.get('#' + fields[0]).element)
      expect(wrapper.emitted('continue')).toBeUndefined()
    },
  )

  it('uses numeric hints and masks the CVV in demo payment fields', () => {
    const wrapper = mount(StepPayment, { global: { plugins: [createPinia(), PrimeVue] } })
    wrappers.push(wrapper)
    for (const field of ['cardNumber', 'expiry', 'cvv'])
      expect(wrapper.get('#' + field).attributes('inputmode')).toBe('numeric')
    expect(wrapper.get('#cvv').attributes('type')).toBe('password')
    expect(wrapper.text()).toContain('No real payment is processed')
  })
})
