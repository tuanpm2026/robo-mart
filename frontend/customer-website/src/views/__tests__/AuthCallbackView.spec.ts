import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AuthCallbackView from '../AuthCallbackView.vue'

const mockHandleCallback = vi.fn()

vi.mock('@/auth/authService', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  renewToken: vi.fn().mockResolvedValue(null),
  getUser: vi.fn().mockResolvedValue(null),
  getAccessToken: vi.fn().mockReturnValue(null),
  loginCallback: vi.fn(),
  saveRedirectPath: vi.fn(),
  consumeRedirectPath: vi.fn().mockReturnValue('/'),
  subscribeToAuthEvents: vi.fn().mockReturnValue(vi.fn()),
}))

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/auth/callback', component: AuthCallbackView },
      { path: '/', component: { template: '<div>Home</div>' } },
      { path: '/cart', component: { template: '<div>Cart</div>' } },
    ],
  })
}

describe('AuthCallbackView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should show loading state initially', async () => {
    // Make handleCallback hang to test loading state
    mockHandleCallback.mockReturnValue(new Promise(() => {}))

    const router = createTestRouter()
    await router.push('/auth/callback')
    await router.isReady()

    const wrapper = mount(AuthCallbackView, {
      global: { plugins: [router, createPinia()] },
    })

    expect(wrapper.text()).toContain('Completing login...')
  })

  it('should show error on callback failure', async () => {
    const { useAuthStore } = await import('@/stores/useAuthStore')
    const router = createTestRouter()
    await router.push('/auth/callback')
    await router.isReady()

    const pinia = createPinia()
    const wrapper = mount(AuthCallbackView, {
      global: { plugins: [router, pinia] },
    })

    // Simulate callback failure by setting store error
    useAuthStore(pinia)
    const { loginCallback } = await import('@/auth/authService')
    vi.mocked(loginCallback).mockRejectedValueOnce(new Error('Invalid state'))

    await flushPromises()

    // The component should display the error
    expect(wrapper.text()).toContain('Authentication failed')
  })
})
