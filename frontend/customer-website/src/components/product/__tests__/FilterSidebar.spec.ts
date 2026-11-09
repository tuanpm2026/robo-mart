import { describe, it, expect, vi } from 'vitest'
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

describe('Filter disclosure', () => {
  it('keeps its controlled content and named range handles mounted while collapsed', async () => {
    const wrapper = mountSidebar()
    const toggle = wrapper.get('button[aria-expanded]')
    const id = toggle.attributes('aria-controls')
    expect(wrapper.get('[id="' + id + '"]').isVisible()).toBe(true)
    const handles = wrapper.findAll('[role="slider"]')
    expect(handles.map((handle) => handle.attributes('aria-label'))).toEqual([
      'Minimum price',
      'Maximum price',
    ])
    await handles[0]!.trigger('keydown', { code: 'ArrowRight', key: 'ArrowRight' })
    expect(wrapper.emitted('filter')?.slice(-1)[0]?.[0]).toMatchObject({ minPrice: 10 })
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[id="' + id + '"]').isVisible()).toBe(false)
    await toggle.trigger('click')
    expect(wrapper.get('[id="' + id + '"]').isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('starts closed on mobile and follows breakpoint changes without duplicate controls', async () => {
    let listener: (() => void) | undefined
    const media = {
      matches: true,
      addEventListener: vi.fn((_: string, fn: () => void) => {
        listener = fn
      }),
      removeEventListener: vi.fn(),
    }
    vi.stubGlobal('matchMedia', () => media)
    const wrapper = mountSidebar()
    expect(wrapper.get('[aria-expanded]').attributes('aria-expanded')).toBe('false')
    media.matches = false
    listener?.()
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[aria-expanded]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[role="slider"]')).toHaveLength(2)
    wrapper.unmount()
    expect(media.removeEventListener).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
  it('returns focus to the disclosure when a resize hides the focused filter control', async () => {
    let listener: (() => void) | undefined
    const media = {
      matches: false,
      addEventListener: (_: string, fn: () => void) => {
        listener = fn
      },
      removeEventListener: vi.fn(),
    }
    vi.stubGlobal('matchMedia', () => media)
    const wrapper = mount(FilterSidebar, {
      attachTo: document.body,
      props: { brands: ['Fixture'] },
      global: { plugins: [PrimeVue] },
    })
    try {
      const slider = wrapper.get<HTMLElement>('[aria-label="Minimum price"]')
      slider.element.focus()
      expect(document.activeElement).toBe(slider.element)
      media.matches = true
      listener?.()
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      expect(document.activeElement).toBe(wrapper.get('button[aria-expanded]').element)
      expect(wrapper.get('[aria-expanded]').attributes('aria-expanded')).toBe('false')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })
})
