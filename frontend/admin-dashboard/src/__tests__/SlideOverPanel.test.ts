import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { adminTheme } from '@robo-mart/shared'
import SlideOverPanel from '../components/SlideOverPanel.vue'

// Stub Drawer to avoid teleport/portal issues in jsdom
const DrawerStub = {
  name: 'Drawer',
  template: '<div class="p-drawer-stub"><slot name="header" /><slot /></div>',
  props: ['visible', 'position', 'style', 'dismissable', 'modal'],
}

function globalPlugins() {
  return [createPinia(), [PrimeVue, { theme: { preset: adminTheme } }]]
}

describe('SlideOverPanel', () => {
  it('Drawer is not visible when visible prop is false', async () => {
    const wrapper = mount(SlideOverPanel, {
      props: { visible: false, title: 'Test Panel' },
      global: {
        plugins: globalPlugins(),
        stubs: { Drawer: DrawerStub },
      },
    })

    const drawer = wrapper.findComponent(DrawerStub)
    expect(drawer.exists()).toBe(true)
    expect(drawer.props('visible')).toBe(false)
  })

  it('Drawer is visible when visible prop is true', async () => {
    const wrapper = mount(SlideOverPanel, {
      props: { visible: true, title: 'Test Panel' },
      global: {
        plugins: globalPlugins(),
        stubs: { Drawer: DrawerStub },
      },
    })

    const drawer = wrapper.findComponent(DrawerStub)
    expect(drawer.props('visible')).toBe(true)
  })

  it('renders title prop in header slot content', async () => {
    const wrapper = mount(SlideOverPanel, {
      props: { visible: true, title: 'Product Details' },
      global: {
        plugins: globalPlugins(),
        stubs: { Drawer: DrawerStub },
      },
    })

    expect(wrapper.find('.slide-over-title').text()).toBe('Product Details')
  })
})
