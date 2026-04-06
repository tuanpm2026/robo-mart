import { describe, it, expect, beforeEach } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuthStore } from '../stores/useAdminAuthStore'

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
  beforeEach(() => {
    setActivePinia(createPinia())
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
    const router = createTestRouter()
    const adminAuthStore = useAdminAuthStore()

    // Simulate admin token in localStorage
    const payload = { sub: 'user-1', preferred_username: 'admin', realm_access: { roles: ['ADMIN'] } }
    const token = `header.${btoa(JSON.stringify(payload))}.signature`
    localStorage.setItem('admin_access_token', token)

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
    localStorage.removeItem('admin_access_token')
  })

  it('isAdmin is false when store has no user', () => {
    const adminAuthStore = useAdminAuthStore()
    expect(adminAuthStore.isAdmin).toBe(false)
  })

  it('isAdmin is true when store user has ADMIN role', async () => {
    const adminAuthStore = useAdminAuthStore()
    const payload = { sub: 'user-1', preferred_username: 'admin', realm_access: { roles: ['ADMIN'] } }
    const token = `header.${btoa(JSON.stringify(payload))}.signature`
    localStorage.setItem('admin_access_token', token)

    await adminAuthStore.initAuth()

    expect(adminAuthStore.isAdmin).toBe(true)
    localStorage.removeItem('admin_access_token')
  })

  it('redirects when user is authenticated but lacks ADMIN role', async () => {
    const router = createTestRouter()
    const adminAuthStore = useAdminAuthStore()

    const payload = { sub: 'user-2', preferred_username: 'editor', realm_access: { roles: ['EDITOR'] } }
    const token = `header.${btoa(JSON.stringify(payload))}.signature`
    localStorage.setItem('admin_access_token', token)

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
    localStorage.removeItem('admin_access_token')
  })

  it('isAdmin is false when token has malformed base64 payload', async () => {
    const adminAuthStore = useAdminAuthStore()
    const token = 'header.!!!invalid_base64!!!.signature'
    localStorage.setItem('admin_access_token', token)

    await adminAuthStore.initAuth()

    expect(adminAuthStore.isAdmin).toBe(false)
    expect(adminAuthStore.user).toBeNull()
    localStorage.removeItem('admin_access_token')
  })
})
