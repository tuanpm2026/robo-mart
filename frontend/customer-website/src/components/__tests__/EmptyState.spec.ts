import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { EmptyState } from '@robo-mart/shared'

describe('EmptyState', () => {
  it('should render search-results variant with default text', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'search-results' },
    })

    expect(wrapper.find('.empty-state__title').text()).toBe('No results found')
    expect(wrapper.find('.empty-state__description').text()).toBe(
      'Try different keywords or filters',
    )
    expect(wrapper.find('.empty-state__cta').text()).toBe('Clear Filters')
  })

  it('should render SVG illustration with aria-hidden', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'search-results' },
    })

    const svg = wrapper.find('.empty-state__illustration')
    expect(svg.exists()).toBe(true)
    expect(svg.attributes('aria-hidden')).toBe('true')
  })

  it('should render CTA button that is keyboard focusable', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'search-results' },
    })

    const cta = wrapper.find('.empty-state__cta')
    expect(cta.exists()).toBe(true)
    expect(cta.element.tagName).toBe('BUTTON')
    expect(cta.attributes('type')).toBe('button')
  })

  it('should emit action event when CTA is clicked', async () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'search-results' },
    })

    await wrapper.find('.empty-state__cta').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('should accept custom title and description', () => {
    const wrapper = mount(EmptyState, {
      props: {
        variant: 'generic',
        title: 'Custom Title',
        description: 'Custom Description',
        ctaLabel: 'Custom Action',
      },
    })

    expect(wrapper.find('.empty-state__title').text()).toBe('Custom Title')
    expect(wrapper.find('.empty-state__description').text()).toBe('Custom Description')
    expect(wrapper.find('.empty-state__cta').text()).toBe('Custom Action')
  })

  it('should render cart variant with default text', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'cart' },
    })

    expect(wrapper.find('.empty-state__title').text()).toBe('Your cart is empty')
    expect(wrapper.find('.empty-state__cta').text()).toBe('Browse Products')
  })

  it('should have status role for screen readers', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'search-results' },
    })

    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })
})
