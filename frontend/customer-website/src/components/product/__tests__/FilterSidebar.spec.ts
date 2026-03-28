import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import FilterSidebar from '../FilterSidebar.vue'

function mountSidebar(props = {}) {
  return mount(FilterSidebar, {
    props: {
      brands: ['BrandA', 'BrandB'],
      ...props,
    },
    global: { plugins: [PrimeVue] },
  })
}

describe('FilterSidebar', () => {
  it('should render filters title', () => {
    const wrapper = mountSidebar()
    expect(wrapper.find('.filter-sidebar__title').text()).toBe('Filters')
  })

  it('should render brand checkboxes', () => {
    const wrapper = mountSidebar()
    expect(wrapper.text()).toContain('Brand')
    expect(wrapper.text()).toContain('BrandA')
    expect(wrapper.text()).toContain('BrandB')
  })

  it('should render price section', () => {
    const wrapper = mountSidebar()
    expect(wrapper.text()).toContain('Price')
  })

  it('should render minimum rating section', () => {
    const wrapper = mountSidebar()
    expect(wrapper.text()).toContain('Minimum Rating')
  })

  it('should render clear filters button', () => {
    const wrapper = mountSidebar()
    expect(wrapper.text()).toContain('Clear Filters')
  })

  it('should have accessible aria-label', () => {
    const wrapper = mountSidebar()
    expect(wrapper.find('[aria-label="Search filters"]').exists()).toBe(true)
  })

  it('should render collapse toggle button', () => {
    const wrapper = mountSidebar()
    const toggle = wrapper.find('[aria-label="Collapse filters"]')
    expect(toggle.exists()).toBe(true)
  })

  it('should hide empty brand section when no brands', () => {
    const wrapper = mountSidebar({ brands: [] })
    const sections = wrapper.findAll('.filter-sidebar__section-title')
    const brandSection = sections.find((s) => s.text() === 'Brand')
    expect(brandSection).toBeUndefined()
  })
})
