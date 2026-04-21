import { createRouter, createWebHistory } from 'vue-router'
import { useAdminAuthStore } from '@/stores/useAdminAuthStore'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/admin/dashboard' },
    { path: '/admin', redirect: '/admin/dashboard' },
    {
      path: '/admin/dashboard',
      name: 'admin-dashboard',
      component: () => import('../views/DashboardPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/products',
      name: 'admin-products',
      component: () => import('../views/ProductsPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/inventory',
      name: 'admin-inventory',
      component: () => import('../views/InventoryPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/orders',
      name: 'admin-orders',
      component: () => import('../views/OrdersPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/reports',
      name: 'admin-reports',
      component: () => import('../views/ReportsPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/system/events',
      name: 'admin-system-events',
      component: () => import('../views/SystemEventsPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/system/health',
      name: 'admin-system-health',
      component: () => import('../views/SystemHealthPage.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/auth/callback',
      name: 'admin-auth-callback',
      component: () => import('../views/AuthCallbackView.vue'),
      meta: { noLayout: true },
    },
    {
      path: '/admin/unauthorized',
      name: 'admin-unauthorized',
      component: () => import('../views/UnauthorizedView.vue'),
      meta: { noLayout: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { noLayout: true },
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAdmin) {
    const adminAuthStore = useAdminAuthStore()
    await adminAuthStore.initAuth()
    if (!adminAuthStore.isAuthenticated) {
      await adminAuthStore.login()
      return false
    }
    if (!adminAuthStore.isAdmin) {
      return { name: 'admin-unauthorized' }
    }
  }
  return true
})

export default router
