import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import ProductCardSkeleton from '../ProductCardSkeleton.vue'

describe('ProductCardSkeleton', () => {
  it('should render skeleton container', () => {
    const wrapper = mount(ProductCardSkeleton, {
      global: { plugins: [PrimeVue] },
    })
    expect(wrapper.find('.product-card-skeleton').exists()).toBe(true)
  })

  it('should be hidden from screen readers', () => {
    const wrapper = mount(ProductCardSkeleton, {
      global: { plugins: [PrimeVue] },
    })
    expect(wrapper.find('.product-card-skeleton').attributes('aria-hidden')).toBe('true')
  })

  it('should render image skeleton', () => {
    const wrapper = mount(ProductCardSkeleton, {
      global: { plugins: [PrimeVue] },
    })
    expect(wrapper.find('.product-card-skeleton__image').exists()).toBe(true)
  })

  it('should render body skeleton elements', () => {
    const wrapper = mount(ProductCardSkeleton, {
      global: { plugins: [PrimeVue] },
    })
    expect(wrapper.find('.product-card-skeleton__body').exists()).toBe(true)
  })
})
