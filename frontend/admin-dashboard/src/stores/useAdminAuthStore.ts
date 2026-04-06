import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

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
  const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)

  function initAuth(): Promise<void> {
    if (initPromise !== null) return initPromise
    initPromise = (async () => {
      const token = localStorage.getItem('admin_access_token')
      if (!token) return

      try {
        const parts = token.split('.')
        if (parts.length !== 3) return
        // Handle URL-safe base64 (RFC 4648 §5) used by Keycloak JWTs
        const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
        const padded = b64.padEnd(b64.length + (4 - (b64.length % 4)) % 4, '=')
        const payload = JSON.parse(atob(padded))
        // Reject expired tokens
        if (payload.exp && payload.exp * 1000 < Date.now()) {
          localStorage.removeItem('admin_access_token')
          return
        }
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

  function logout() {
    localStorage.removeItem('admin_access_token')
    accessToken.value = null
    user.value = null
    initPromise = null
    window.location.href = '/admin/unauthorized'
  }

  return { accessToken, user, isAuthenticated, isAdmin, initAuth, logout }
})
