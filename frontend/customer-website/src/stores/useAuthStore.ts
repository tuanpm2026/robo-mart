import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { AuthUser } from '@/types/auth'
import type { User } from 'oidc-client-ts'
import { useCartStore } from '@/stores/useCartStore'
import {
  login as authLogin,
  loginCallback,
  register as authRegister,
  logout as authLogout,
  renewToken,
  getUser,
  getAccessToken,
  saveRedirectPath,
  consumeRedirectPath,
  subscribeToAuthEvents,
} from '@/auth/authService'

function mapOidcUser(oidcUser: User): AuthUser {
  const profile = oidcUser.profile
  return {
    id: profile.sub,
    email: (profile.email as string) || '',
    firstName: (profile.given_name as string) || '',
    lastName: (profile.family_name as string) || '',
    roles: (profile.realm_access as { roles?: string[] })?.roles || [],
  }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const accessToken = ref<string | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!user.value && !!accessToken.value)
  const displayName = computed(() => {
    if (!user.value) return ''
    if (user.value.firstName) return user.value.firstName
    return user.value.email
  })

  let unsubscribeEvents: (() => void) | null = null

  async function initAuth(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      const oidcUser = await getUser()
      const token = getAccessToken(oidcUser)
      if (oidcUser && token) {
        user.value = mapOidcUser(oidcUser)
        accessToken.value = token
      } else if (oidcUser && !token) {
        // Token expired, try silent refresh
        const renewed = await renewToken()
        if (renewed && !renewed.expired && renewed.access_token) {
          user.value = mapOidcUser(renewed)
          accessToken.value = renewed.access_token
        } else {
          user.value = null
          accessToken.value = null
        }
      }
    } catch {
      // OIDC unavailable or stored session invalid — fall back to unauthenticated
      user.value = null
      accessToken.value = null
    } finally {
      isLoading.value = false
    }

    // Merge anonymous cart if authenticated and localStorage still has anonymous ID
    if (user.value && accessToken.value && localStorage.getItem('robomart-user-id')) {
      try {
        await useCartStore().mergeAnonymousCart()
      } catch {
        // Non-blocking — merge failure should not break init
      }
    }

    // Subscribe to OIDC events for cross-tab sync and silent renewal updates
    if (!unsubscribeEvents) {
      unsubscribeEvents = subscribeToAuthEvents({
        onUserLoaded(oidcUser) {
          user.value = mapOidcUser(oidcUser)
          accessToken.value = oidcUser.access_token
        },
        onUserUnloaded() {
          user.value = null
          accessToken.value = null
        },
        onSilentRenewError() {
          user.value = null
          accessToken.value = null
        },
      })
    }
  }

  async function login(idpHint?: string, loginHint?: string, redirectTo?: string): Promise<void> {
    error.value = null
    const params: Record<string, string> = {}
    if (idpHint) {
      params.kc_idp_hint = idpHint
    }
    if (loginHint) {
      params.login_hint = loginHint
    }
    const path = redirectTo ?? (window.location.pathname + window.location.search + window.location.hash)
    saveRedirectPath(path)
    await authLogin(Object.keys(params).length > 0 ? params : undefined)
  }

  async function register(): Promise<void> {
    error.value = null
    saveRedirectPath(window.location.pathname + window.location.search + window.location.hash)
    await authRegister()
  }

  async function handleCallback(): Promise<string> {
    isLoading.value = true
    error.value = null
    try {
      const oidcUser = await loginCallback()
      user.value = mapOidcUser(oidcUser)
      accessToken.value = oidcUser.access_token

      // Merge anonymous cart after login (non-blocking)
      try {
        await useCartStore().mergeAnonymousCart()
      } catch {
        // Merge failure should not block login flow
      }

      return consumeRedirectPath()
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Authentication failed'
      throw err
    } finally {
      isLoading.value = false
    }
  }

  async function refreshToken(): Promise<boolean> {
    try {
      const renewed = await renewToken()
      if (renewed) {
        user.value = mapOidcUser(renewed)
        accessToken.value = renewed.access_token
        return true
      }
      return false
    } catch {
      user.value = null
      accessToken.value = null
      return false
    }
  }

  async function logout(): Promise<void> {
    try {
      await authLogout()
    } catch {
      // Keycloak might be unreachable — clear local state anyway
    } finally {
      user.value = null
      accessToken.value = null
      error.value = null
    }
  }

  function $reset() {
    user.value = null
    accessToken.value = null
    isLoading.value = false
    error.value = null
  }

  return {
    user,
    accessToken,
    isLoading,
    error,
    isAuthenticated,
    displayName,
    initAuth,
    login,
    register,
    handleCallback,
    refreshToken,
    logout,
    $reset,
  }
})
