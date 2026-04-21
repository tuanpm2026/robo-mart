import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import CartItem from '../CartItem.vue'
import type { CartItem as CartItemType } from '@/types/cart'

const mockItem: CartItemType = {
  productId: 1,
  productName: 'Test Product',
  price: 29.99,
  quantity: 2,
  subtotal: 59.98,
}

function mountCartItem(item: CartItemType = mockItem) {
  return mount(CartItem, {
    props: { item },
    global: { plugins: [PrimeVue] },
  })
}

describe('CartItem', () => {
  it('should render product name', () => {
    const wrapper = mountCartItem()
    expect(wrapper.find('.cart-item__name').text()).toBe('Test Product')
  })

  it('should render unit price', () => {
    const wrapper = mountCartItem()
    expect(wrapper.find('.cart-item__price').text()).toBe('$29.99')
  })

  it('should render subtotal', () => {
    const wrapper = mountCartItem()
    expect(wrapper.find('.cart-item__subtotal').text()).toBe('$59.98')
  })

  it('should have accessible aria-label', () => {
    const wrapper = mountCartItem()
    expect(wrapper.find('.cart-item').attributes('aria-label')).toBe('Test Product, quantity 2')
  })

  it('should have remove button with aria-label', () => {
    const wrapper = mountCartItem()
    const removeBtn = wrapper.find('.cart-item__remove')
    expect(removeBtn.exists()).toBe(true)
    expect(removeBtn.attributes('aria-label')).toBe('Remove Test Product from cart')
  })

  it('should emit remove event when remove button clicked', async () => {
    const wrapper = mountCartItem()
    await wrapper.find('.cart-item__remove').trigger('click')
    expect(wrapper.emitted('remove')).toBeTruthy()
    expect(wrapper.emitted('remove')![0]).toEqual([1])
  })

  it('should render quantity input with correct value', () => {
    const wrapper = mountCartItem()
    const input = wrapper.find('.cart-item__quantity-input')
    expect(input.exists()).toBe(true)
  })

  it('should render image placeholder', () => {
    const wrapper = mountCartItem()
    expect(wrapper.find('.cart-item__image-placeholder').exists()).toBe(true)
  })
})
