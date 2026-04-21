import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../useAuthStore'

const mockOidcUser = {
  access_token: 'mock-access-token',
  expired: false,
  profile: {
    sub: 'user-uuid-123',
    email: 'test@example.com',
    given_name: 'John',
    family_name: 'Doe',
    realm_access: { roles: ['CUSTOMER'] },
  },
}

const mockLogin = vi.fn()
const mockRegister = vi.fn()
const mockLogout = vi.fn()
const mockRenewToken = vi.fn()
const mockGetUser = vi.fn()
const mockGetAccessToken = vi.fn()
const mockLoginCallback = vi.fn()
const mockSaveRedirectPath = vi.fn()
const mockConsumeRedirectPath = vi.fn().mockReturnValue('/cart')
const mockSubscribeToAuthEvents = vi.fn().mockReturnValue(vi.fn())

const mockMergeAnonymousCart = vi.fn().mockResolvedValue(undefined)

vi.mock('@/stores/useCartStore', () => ({
  useCartStore: () => ({
    mergeAnonymousCart: mockMergeAnonymousCart,
  }),
}))

vi.mock('@/auth/authService', () => ({
  login: (...args: unknown[]) => mockLogin(...args),
  register: (...args: unknown[]) => mockRegister(...args),
  logout: (...args: unknown[]) => mockLogout(...args),
  renewToken: (...args: unknown[]) => mockRenewToken(...args),
  getUser: (...args: unknown[]) => mockGetUser(...args),
  getAccessToken: (...args: unknown[]) => mockGetAccessToken(...args),
  loginCallback: (...args: unknown[]) => mockLoginCallback(...args),
  saveRedirectPath: (...args: unknown[]) => mockSaveRedirectPath(...args),
  consumeRedirectPath: (...args: unknown[]) => mockConsumeRedirectPath(...args),
  subscribeToAuthEvents: (...args: unknown[]) => mockSubscribeToAuthEvents(...args),
}))

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should start with unauthenticated state', () => {
    const store = useAuthStore()
    expect(store.user).toBeNull()
    expect(store.accessToken).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
    expect(store.displayName).toBe('')
  })

  describe('initAuth', () => {
    it('should restore session when valid user exists in storage', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')

      const store = useAuthStore()
      await store.initAuth()

      expect(store.isAuthenticated).toBe(true)
      expect(store.user?.id).toBe('user-uuid-123')
      expect(store.user?.email).toBe('test@example.com')
      expect(store.user?.firstName).toBe('John')
      expect(store.accessToken).toBe('mock-access-token')
      expect(store.isLoading).toBe(false)
    })

    it('should attempt silent refresh when token is expired', async () => {
      const expiredUser = { ...mockOidcUser, expired: true }
      mockGetUser.mockResolvedValue(expiredUser)
      mockGetAccessToken.mockReturnValue(null)
      mockRenewToken.mockResolvedValue(mockOidcUser)

      const store = useAuthStore()
      await store.initAuth()

      expect(mockRenewToken).toHaveBeenCalled()
      expect(store.isAuthenticated).toBe(true)
      expect(store.accessToken).toBe('mock-access-token')
    })

    it('should clear state when refresh fails for expired token', async () => {
      const expiredUser = { ...mockOidcUser, expired: true }
      mockGetUser.mockResolvedValue(expiredUser)
      mockGetAccessToken.mockReturnValue(null)
      mockRenewToken.mockResolvedValue(null)

      const store = useAuthStore()
      await store.initAuth()

      expect(store.isAuthenticated).toBe(false)
      expect(store.user).toBeNull()
    })

    it('should handle OIDC unavailable gracefully', async () => {
      mockGetUser.mockRejectedValue(new Error('Network error'))

      const store = useAuthStore()
      await store.initAuth()

      expect(store.isAuthenticated).toBe(false)
      expect(store.user).toBeNull()
      expect(store.error).toBeNull() // Should not set error for init failures
    })

    it('should remain unauthenticated when no stored user', async () => {
      mockGetUser.mockResolvedValue(null)

      const store = useAuthStore()
      await store.initAuth()

      expect(store.isAuthenticated).toBe(false)
    })

    it('should merge anonymous cart when authenticated and localStorage has anonymous ID', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')
      localStorage.setItem('robomart-user-id', 'anon-uuid-init')

      const store = useAuthStore()
      await store.initAuth()

      expect(mockMergeAnonymousCart).toHaveBeenCalled()
    })

    it('should not merge anonymous cart when no anonymous ID in localStorage', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')
      localStorage.removeItem('robomart-user-id')

      const store = useAuthStore()
      await store.initAuth()

      expect(mockMergeAnonymousCart).not.toHaveBeenCalled()
    })

    it('should proceed with init even if merge fails', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')
      localStorage.setItem('robomart-user-id', 'anon-uuid-init')
      mockMergeAnonymousCart.mockRejectedValueOnce(new Error('Merge failed'))

      const store = useAuthStore()
      await store.initAuth()

      expect(store.isAuthenticated).toBe(true)
      expect(store.accessToken).toBe('mock-access-token')
    })

    it('should subscribe to auth events on init', async () => {
      mockGetUser.mockResolvedValue(null)

      const store = useAuthStore()
      await store.initAuth()

      expect(mockSubscribeToAuthEvents).toHaveBeenCalledWith({
        onUserLoaded: expect.any(Function),
        onUserUnloaded: expect.any(Function),
        onSilentRenewError: expect.any(Function),
      })
    })

    it('should update state when onUserLoaded event fires', async () => {
      mockGetUser.mockResolvedValue(null)
      let capturedCallbacks: Record<string, Function> = {}
      mockSubscribeToAuthEvents.mockImplementation((cbs: Record<string, Function>) => {
        capturedCallbacks = cbs
        return vi.fn()
      })

      const store = useAuthStore()
      await store.initAuth()

      capturedCallbacks.onUserLoaded!(mockOidcUser)

      expect(store.isAuthenticated).toBe(true)
      expect(store.user?.id).toBe('user-uuid-123')
      expect(store.accessToken).toBe('mock-access-token')
    })

    it('should clear state when onUserUnloaded event fires', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')
      let capturedCallbacks: Record<string, Function> = {}
      mockSubscribeToAuthEvents.mockImplementation((cbs: Record<string, Function>) => {
        capturedCallbacks = cbs
        return vi.fn()
      })

      const store = useAuthStore()
      await store.initAuth()
      expect(store.isAuthenticated).toBe(true)

      capturedCallbacks.onUserUnloaded!()

      expect(store.isAuthenticated).toBe(false)
      expect(store.user).toBeNull()
    })

    it('should clear state when onSilentRenewError event fires', async () => {
      mockGetUser.mockResolvedValue(mockOidcUser)
      mockGetAccessToken.mockReturnValue('mock-access-token')
      let capturedCallbacks: Record<string, Function> = {}
      mockSubscribeToAuthEvents.mockImplementation((cbs: Record<string, Function>) => {
        capturedCallbacks = cbs
        return vi.fn()
      })

      const store = useAuthStore()
      await store.initAuth()

      capturedCallbacks.onSilentRenewError!(new Error('renewal failed'))

      expect(store.isAuthenticated).toBe(false)
      expect(store.user).toBeNull()
    })
  })

  describe('login', () => {
    it('should save redirect path and call auth login', async () => {
      const store = useAuthStore()
      await store.login()

      expect(mockSaveRedirectPath).toHaveBeenCalled()
      expect(mockLogin).toHaveBeenCalledWith(undefined)
    })

    it('should pass idp hint for social login', async () => {
      const store = useAuthStore()
      await store.login('google')

      expect(mockLogin).toHaveBeenCalledWith({ kc_idp_hint: 'google' })
    })

    it('should pass login_hint when loginHint provided', async () => {
      const store = useAuthStore()
      await store.login(undefined, 'user@example.com')

      expect(mockLogin).toHaveBeenCalledWith({ login_hint: 'user@example.com' })
    })

    it('should pass both kc_idp_hint and login_hint when both provided', async () => {
      const store = useAuthStore()
      await store.login('google', 'user@example.com')

      expect(mockLogin).toHaveBeenCalledWith({
        kc_idp_hint: 'google',
        login_hint: 'user@example.com',
      })
    })

    it('should clear error before login', async () => {
      const store = useAuthStore()
      store.error = 'previous error'
      await store.login()

      expect(store.error).toBeNull()
    })
  })

  describe('register', () => {
    it('should save redirect path and call auth register', async () => {
      const store = useAuthStore()
      await store.register()

      expect(mockSaveRedirectPath).toHaveBeenCalled()
      expect(mockRegister).toHaveBeenCalled()
    })
  })

  describe('handleCallback', () => {
    it('should populate store and return redirect path on success', async () => {
      mockLoginCallback.mockResolvedValue(mockOidcUser)

      const store = useAuthStore()
      const path = await store.handleCallback()

      expect(store.isAuthenticated).toBe(true)
      expect(store.user?.email).toBe('test@example.com')
      expect(store.accessToken).toBe('mock-access-token')
      expect(path).toBe('/cart')
      expect(mockConsumeRedirectPath).toHaveBeenCalled()
    })

    it('should set error and throw on callback failure', async () => {
      mockLoginCallback.mockRejectedValue(new Error('Invalid state'))

      const store = useAuthStore()
      await expect(store.handleCallback()).rejects.toThrow('Invalid state')

      expect(store.error).toBe('Invalid state')
      expect(store.isAuthenticated).toBe(false)
    })

    it('should set isLoading during callback', async () => {
      mockLoginCallback.mockResolvedValue(mockOidcUser)

      const store = useAuthStore()
      const promise = store.handleCallback()
      expect(store.isLoading).toBe(true)
      await promise
      expect(store.isLoading).toBe(false)
    })
  })

  describe('refreshToken', () => {
    it('should update state on successful refresh', async () => {
      mockRenewToken.mockResolvedValue(mockOidcUser)

      const store = useAuthStore()
      const result = await store.refreshToken()

      expect(result).toBe(true)
      expect(store.accessToken).toBe('mock-access-token')
      expect(store.user?.id).toBe('user-uuid-123')
    })

    it('should return false when refresh returns null', async () => {
      mockRenewToken.mockResolvedValue(null)

      const store = useAuthStore()
      const result = await store.refreshToken()

      expect(result).toBe(false)
    })

    it('should clear state on refresh error', async () => {
      mockRenewToken.mockRejectedValue(new Error('Refresh failed'))

      const store = useAuthStore()
      store.user = { id: 'test', email: 'test@test.com', firstName: '', lastName: '', roles: [] }
      store.accessToken = 'old-token'

      const result = await store.refreshToken()

      expect(result).toBe(false)
      expect(store.user).toBeNull()
      expect(store.accessToken).toBeNull()
    })
  })

  describe('logout', () => {
    it('should clear state on logout', async () => {
      mockLogout.mockResolvedValue(undefined)

      const store = useAuthStore()
      store.user = {
        id: 'test',
        email: 'test@test.com',
        firstName: 'Test',
        lastName: '',
        roles: [],
      }
      store.accessToken = 'token'

      await store.logout()

      expect(store.user).toBeNull()
      expect(store.accessToken).toBeNull()
      expect(store.isAuthenticated).toBe(false)
    })

    it('should clear state even if Keycloak logout fails', async () => {
      mockLogout.mockRejectedValue(new Error('Keycloak unreachable'))

      const store = useAuthStore()
      store.user = { id: 'test', email: 'test@test.com', firstName: '', lastName: '', roles: [] }
      store.accessToken = 'token'

      await store.logout()

      expect(store.user).toBeNull()
      expect(store.accessToken).toBeNull()
    })
  })

  describe('displayName', () => {
    it('should return firstName when available', () => {
      const store = useAuthStore()
      store.user = {
        id: 'test',
        email: 'test@test.com',
        firstName: 'John',
        lastName: 'Doe',
        roles: [],
      }

      expect(store.displayName).toBe('John')
    })

    it('should fallback to email when firstName is empty', () => {
      const store = useAuthStore()
      store.user = { id: 'test', email: 'test@test.com', firstName: '', lastName: '', roles: [] }

      expect(store.displayName).toBe('test@test.com')
    })

    it('should return empty string when no user', () => {
      const store = useAuthStore()
      expect(store.displayName).toBe('')
    })
  })

  // === Story 3.4: Cart Merge on Login ===

  describe('cart merge on handleCallback', () => {
    it('shouldMergeCartDuringLoginCallback', async () => {
      mockLoginCallback.mockResolvedValue(mockOidcUser)

      const store = useAuthStore()
      await store.handleCallback()

      expect(mockMergeAnonymousCart).toHaveBeenCalled()
    })

    it('shouldProceedWithRedirectEvenIfMergeFails', async () => {
      mockLoginCallback.mockResolvedValue(mockOidcUser)
      mockMergeAnonymousCart.mockRejectedValueOnce(new Error('Merge failed'))

      const store = useAuthStore()
      const path = await store.handleCallback()

      expect(path).toBe('/cart')
      expect(store.isAuthenticated).toBe(true)
    })
  })

  describe('$reset', () => {
    it('should reset all state', () => {
      const store = useAuthStore()
      store.user = { id: 'test', email: 'test@test.com', firstName: '', lastName: '', roles: [] }
      store.accessToken = 'token'
      store.isLoading = true
      store.error = 'some error'

      store.$reset()

      expect(store.user).toBeNull()
      expect(store.accessToken).toBeNull()
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    })
  })
})
