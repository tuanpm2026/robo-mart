import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { useAuthStore } from '@/stores/useAuthStore'
import DefaultLayout from '../DefaultLayout.vue'

vi.mock('@/api/productApi', () => ({
  searchProducts: vi.fn().mockResolvedValue({
    data: [],
    pagination: { page: 0, size: 5, totalElements: 0, totalPages: 0 },
    traceId: '',
  }),
}))

vi.mock('@/auth/authService', () => ({
  getUser: vi.fn().mockResolvedValue(null),
  getAccessToken: vi.fn().mockReturnValue(null),
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  renewToken: vi.fn().mockResolvedValue(null),
  saveRedirectPath: vi.fn(),
  consumeRedirectPath: vi.fn().mockReturnValue('/'),
  subscribeToAuthEvents: vi.fn().mockReturnValue(vi.fn()),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div>Home</div>' } }],
  })
}

function mountLayout() {
  const router = createTestRouter()
  const pinia = createPinia()
  return {
    router,
    pinia,
    mount: async () => {
      await router.push('/')
      await router.isReady()
      return mount(DefaultLayout, {
        global: { plugins: [router, pinia, PrimeVue, ToastService] },
      })
    },
  }
}

describe('DefaultLayout', () => {
  it('should render header element', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('header.header').exists()).toBe(true)
  })

  it('should render category nav with aria-label', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('nav[aria-label="Product categories"]').exists()).toBe(true)
  })

  it('should render main content area with id for skip link', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('main#main-content').exists()).toBe(true)
  })

  it('should render footer element', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('footer.footer').exists()).toBe(true)
  })

  it('should render logo linking to home', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    const logo = wrapper.find('.header__logo a')
    expect(logo.exists()).toBe(true)
    expect(logo.text()).toContain('RoboMart')
  })

  it('should render search bar component', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('.search-bar').exists()).toBe(true)
  })

  it('should render user button with Log in label when unauthenticated', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    expect(wrapper.find('[aria-label="Shopping cart, 0 items"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Log in"]').exists()).toBe(true)
  })

  it('should render category navigation links', async () => {
    const { mount: m } = mountLayout()
    const wrapper = await m()
    const links = wrapper.findAll('.category-nav__link')
    expect(links.length).toBeGreaterThan(1)
    expect(links[0]!.text()).toBe('All')
  })

  it('should show user name and menu button when authenticated', async () => {
    const { mount: m, pinia } = mountLayout()
    const store = useAuthStore(pinia)
    store.user = {
      id: 'u1',
      email: 'john@test.com',
      firstName: 'John',
      lastName: 'Doe',
      roles: ['CUSTOMER'],
    }
    store.accessToken = 'token-123'

    const wrapper = await m()
    expect(wrapper.find('[aria-label="User menu"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('John')
  })
})
