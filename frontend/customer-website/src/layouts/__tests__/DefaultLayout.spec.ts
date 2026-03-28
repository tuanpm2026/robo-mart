import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import DefaultLayout from '../DefaultLayout.vue'

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div>Home</div>' } }],
  })
}

describe('DefaultLayout', () => {
  it('should render header with banner role', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const header = wrapper.find('header[role="banner"]')
    expect(header.exists()).toBe(true)
  })

  it('should render category nav with aria-label', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const nav = wrapper.find('nav[aria-label="Product categories"]')
    expect(nav.exists()).toBe(true)
  })

  it('should render main content area with id for skip link', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const main = wrapper.find('main#main-content')
    expect(main.exists()).toBe(true)
  })

  it('should render footer with contentinfo role', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const footer = wrapper.find('footer[role="contentinfo"]')
    expect(footer.exists()).toBe(true)
  })

  it('should render logo linking to home', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const logo = wrapper.find('.header__logo a')
    expect(logo.exists()).toBe(true)
    expect(logo.text()).toContain('RoboMart')
  })

  it('should render search placeholder', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const search = wrapper.find('.header__search-placeholder')
    expect(search.exists()).toBe(true)
    expect(search.text()).toContain('Search products')
  })

  it('should render cart and user action buttons', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router] },
    })

    const cartBtn = wrapper.find('[aria-label="Shopping cart, 0 items"]')
    const userBtn = wrapper.find('[aria-label="User menu"]')
    expect(cartBtn.exists()).toBe(true)
    expect(userBtn.exists()).toBe(true)
  })
})
