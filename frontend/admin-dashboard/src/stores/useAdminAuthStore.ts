import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { userManager } from '@/auth/keycloak'

interface AdminUser {
  id: string
  username: string
  roles: string[]
}

export const useAdminAuthStore = defineStore('adminAuth', () => {
  const accessToken = ref<string | null>(null)
  const user = ref<AdminUser | null>(null)
  let initPromise: Promise<void> | null = null

  const isAuthenticated = computed(() => !!accessToken.value)
  const isAdmin = computed(
    () => user.value?.roles.some((r) => r.toLowerCase() === 'admin') ?? false,
  )

  function initAuth(): Promise<void> {
    if (initPromise !== null) return initPromise
    initPromise = (async () => {
      const oidcUser = await userManager.getUser()
      if (!oidcUser || oidcUser.expired) return

      const token = oidcUser.access_token
      try {
        const parts = token.split('.')
        if (parts.length !== 3) return
        const b64 = parts[1]!.replace(/-/g, '+').replace(/_/g, '/')
        const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), '=')
        const payload = JSON.parse(atob(padded))
        const roles: string[] = Array.isArray(payload.realm_access?.roles)
          ? payload.realm_access.roles
          : []
        user.value = {
          id: payload.sub ?? '',
          username: payload.preferred_username ?? payload.sub ?? '',
          roles,
        }
        accessToken.value = token
      } catch {
        // Invalid token — ignore
      }
    })()
    return initPromise
  }

  async function login(): Promise<void> {
    await userManager.signinRedirect()
  }

  function resetAuth(): void {
    accessToken.value = null
    user.value = null
    initPromise = null
  }

  async function logout(): Promise<void> {
    resetAuth()
    await userManager.signoutRedirect()
  }

  userManager.events.addUserLoaded((oidcUser) => {
    const token = oidcUser.access_token
    try {
      const parts = token.split('.')
      if (parts.length !== 3) return
      const b64 = parts[1]!.replace(/-/g, '+').replace(/_/g, '/')
      const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), '=')
      const payload = JSON.parse(atob(padded))
      const roles: string[] = Array.isArray(payload.realm_access?.roles)
        ? payload.realm_access.roles
        : []
      user.value = {
        id: payload.sub ?? '',
        username: payload.preferred_username ?? payload.sub ?? '',
        roles,
      }
      accessToken.value = token
    } catch {
      // ignore
    }
  })

  userManager.events.addUserUnloaded(() => {
    accessToken.value = null
    user.value = null
    initPromise = null
  })

  return { accessToken, user, isAuthenticated, isAdmin, initAuth, resetAuth, login, logout }
})
