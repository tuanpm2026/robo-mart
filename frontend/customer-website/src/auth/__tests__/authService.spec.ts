import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockSigninRedirect = vi.fn()
const mockSigninRedirectCallback = vi.fn()
const mockSignoutRedirect = vi.fn()
const mockSigninSilent = vi.fn()
const mockGetUser = vi.fn()
const mockAddUserLoaded = vi.fn()
const mockAddUserUnloaded = vi.fn()
const mockAddSilentRenewError = vi.fn()
const mockRemoveUserLoaded = vi.fn()
const mockRemoveUserUnloaded = vi.fn()
const mockRemoveSilentRenewError = vi.fn()

vi.mock('@/auth/keycloak', () => ({
  userManager: {
    signinRedirect: (...args: unknown[]) => mockSigninRedirect(...args),
    signinRedirectCallback: (...args: unknown[]) => mockSigninRedirectCallback(...args),
    signoutRedirect: (...args: unknown[]) => mockSignoutRedirect(...args),
    signinSilent: (...args: unknown[]) => mockSigninSilent(...args),
    getUser: (...args: unknown[]) => mockGetUser(...args),
    events: {
      addUserLoaded: (...args: unknown[]) => mockAddUserLoaded(...args),
      addUserUnloaded: (...args: unknown[]) => mockAddUserUnloaded(...args),
      addSilentRenewError: (...args: unknown[]) => mockAddSilentRenewError(...args),
      removeUserLoaded: (...args: unknown[]) => mockRemoveUserLoaded(...args),
      removeUserUnloaded: (...args: unknown[]) => mockRemoveUserUnloaded(...args),
      removeSilentRenewError: (...args: unknown[]) => mockRemoveSilentRenewError(...args),
    },
  },
}))

describe('authService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  describe('login', () => {
    it('should call signinRedirect without extra params', async () => {
      const { login } = await import('../authService')
      await login()
      expect(mockSigninRedirect).toHaveBeenCalledWith({})
    })

    it('should pass extra query params for social login', async () => {
      const { login } = await import('../authService')
      await login({ kc_idp_hint: 'google' })
      expect(mockSigninRedirect).toHaveBeenCalledWith({
        extraQueryParams: { kc_idp_hint: 'google' },
      })
    })
  })

  describe('register', () => {
    it('should redirect with kc_action register', async () => {
      const { register } = await import('../authService')
      await register()
      expect(mockSigninRedirect).toHaveBeenCalledWith({
        extraQueryParams: { kc_action: 'register' },
      })
    })
  })

  describe('loginCallback', () => {
    it('should call signinRedirectCallback', async () => {
      const mockUser = { access_token: 'token', profile: { sub: 'id' } }
      mockSigninRedirectCallback.mockResolvedValue(mockUser)

      const { loginCallback } = await import('../authService')
      const result = await loginCallback()

      expect(result).toEqual(mockUser)
      expect(mockSigninRedirectCallback).toHaveBeenCalled()
    })
  })

  describe('logout', () => {
    it('should call signoutRedirect', async () => {
      const { logout } = await import('../authService')
      await logout()
      expect(mockSignoutRedirect).toHaveBeenCalled()
    })
  })

  describe('renewToken', () => {
    it('should call signinSilent', async () => {
      const mockUser = { access_token: 'new-token' }
      mockSigninSilent.mockResolvedValue(mockUser)

      const { renewToken } = await import('../authService')
      const result = await renewToken()

      expect(result).toEqual(mockUser)
    })
  })

  describe('getUser', () => {
    it('should delegate to userManager.getUser', async () => {
      const mockUser = { access_token: 'token' }
      mockGetUser.mockResolvedValue(mockUser)

      const { getUser } = await import('../authService')
      const result = await getUser()

      expect(result).toEqual(mockUser)
    })
  })

  describe('getAccessToken', () => {
    it('should return token for non-expired user', async () => {
      const { getAccessToken } = await import('../authService')
      const user = { access_token: 'my-token', expired: false } as never
      expect(getAccessToken(user)).toBe('my-token')
    })

    it('should return null for expired user', async () => {
      const { getAccessToken } = await import('../authService')
      const user = { access_token: 'expired-token', expired: true } as never
      expect(getAccessToken(user)).toBeNull()
    })

    it('should return null for null user', async () => {
      const { getAccessToken } = await import('../authService')
      expect(getAccessToken(null)).toBeNull()
    })
  })

  describe('redirect path management', () => {
    it('should save and consume redirect path', async () => {
      const { saveRedirectPath, consumeRedirectPath } = await import('../authService')
      saveRedirectPath('/cart')
      expect(consumeRedirectPath()).toBe('/cart')
      // Should be consumed — second call returns default
      expect(consumeRedirectPath()).toBe('/')
    })

    it('should reject absolute URL redirect (open redirect prevention)', async () => {
      const { saveRedirectPath, consumeRedirectPath } = await import('../authService')
      saveRedirectPath('https://evil.com/phish')
      expect(consumeRedirectPath()).toBe('/')
    })

    it('should reject protocol-relative URL redirect', async () => {
      const { saveRedirectPath, consumeRedirectPath } = await import('../authService')
      saveRedirectPath('//evil.com')
      expect(consumeRedirectPath()).toBe('/')
    })
  })

  describe('subscribeToAuthEvents', () => {
    it('should register event listeners and return unsubscribe function', async () => {
      const { subscribeToAuthEvents } = await import('../authService')
      const callbacks = {
        onUserLoaded: vi.fn(),
        onUserUnloaded: vi.fn(),
        onSilentRenewError: vi.fn(),
      }

      const unsubscribe = subscribeToAuthEvents(callbacks)

      expect(mockAddUserLoaded).toHaveBeenCalledWith(callbacks.onUserLoaded)
      expect(mockAddUserUnloaded).toHaveBeenCalledWith(callbacks.onUserUnloaded)
      expect(mockAddSilentRenewError).toHaveBeenCalledWith(callbacks.onSilentRenewError)

      unsubscribe()

      expect(mockRemoveUserLoaded).toHaveBeenCalledWith(callbacks.onUserLoaded)
      expect(mockRemoveUserUnloaded).toHaveBeenCalledWith(callbacks.onUserUnloaded)
      expect(mockRemoveSilentRenewError).toHaveBeenCalledWith(callbacks.onSilentRenewError)
    })
  })
})
