import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuthStore } from '../stores/useAdminAuthStore'

function makeOidcUser(payload: object, expired = false) {
  const token = `header.${btoa(JSON.stringify(payload))}.signature`
  return { access_token: token, expired }
}

vi.mock('@/auth/keycloak', () => ({
  userManager: {
    getUser: vi.fn(),
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
    events: {
      addUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
    },
  },
}))

async function importUserManager() {
  const { userManager } = await import('@/auth/keycloak')
  return userManager
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/dashboard',
        name: 'admin-dashboard',
        component: { template: '<div />' },
        meta: { requiresAdmin: true },
      },
      {
        path: '/admin/unauthorized',
        name: 'admin-unauthorized',
        component: { template: '<div />' },
      },
    ],
  })
}

describe('Admin role guard', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    const userManager = await importUserManager()
    vi.mocked(userManager.getUser).mockResolvedValue(null)
  })

  it('redirects to /admin/unauthorized when isAdmin is false', async () => {
    const router = createTestRouter()
    const adminAuthStore = useAdminAuthStore()

    router.beforeEach(async (to) => {
      if (to.meta.requiresAdmin) {
        await adminAuthStore.initAuth()
        if (!adminAuthStore.isAdmin) {
          return { name: 'admin-unauthorized' }
        }
      }
    })

    await router.push('/admin/dashboard')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('admin-unauthorized')
  })

  it('does not redirect when isAdmin is true', async () => {
    const userManager = await importUserManager()
    const payload = {
      sub: 'user-1',
      preferred_username: 'admin',
      realm_access: { roles: ['ADMIN'] },
    }
    vi.mocked(userManager.getUser).mockResolvedValue(makeOidcUser(payload) as never)

    const router = createTestRouter()
    const adminAuthStore = useAdminAuthStore()

    router.beforeEach(async (to) => {
      if (to.meta.requiresAdmin) {
        await adminAuthStore.initAuth()
        if (!adminAuthStore.isAdmin) {
          return { name: 'admin-unauthorized' }
        }
      }
    })

    await router.push('/admin/dashboard')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('admin-dashboard')
  })

  it('isAdmin is false when store has no user', () => {
    const adminAuthStore = useAdminAuthStore()
    expect(adminAuthStore.isAdmin).toBe(false)
  })

  it('isAdmin is true when store user has ADMIN role', async () => {
    const userManager = await importUserManager()
    const payload = {
      sub: 'user-1',
      preferred_username: 'admin',
      realm_access: { roles: ['ADMIN'] },
    }
    vi.mocked(userManager.getUser).mockResolvedValue(makeOidcUser(payload) as never)

    const adminAuthStore = useAdminAuthStore()
    await adminAuthStore.initAuth()

    expect(adminAuthStore.isAdmin).toBe(true)
  })

  it('redirects when user is authenticated but lacks ADMIN role', async () => {
    const userManager = await importUserManager()
    const payload = {
      sub: 'user-2',
      preferred_username: 'editor',
      realm_access: { roles: ['EDITOR'] },
    }
    vi.mocked(userManager.getUser).mockResolvedValue(makeOidcUser(payload) as never)

    const router = createTestRouter()
    const adminAuthStore = useAdminAuthStore()

    router.beforeEach(async (to) => {
      if (to.meta.requiresAdmin) {
        await adminAuthStore.initAuth()
        if (!adminAuthStore.isAdmin) {
          return { name: 'admin-unauthorized' }
        }
      }
      return true
    })

    await router.push('/admin/dashboard')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('admin-unauthorized')
  })

  it('isAdmin is false when token has malformed base64 payload', async () => {
    const userManager = await importUserManager()
    vi.mocked(userManager.getUser).mockResolvedValue({
      access_token: 'header.!!!invalid_base64!!!.signature',
      expired: false,
    } as never)

    const adminAuthStore = useAdminAuthStore()
    await adminAuthStore.initAuth()

    expect(adminAuthStore.isAdmin).toBe(false)
    expect(adminAuthStore.user).toBeNull()
  })
})
