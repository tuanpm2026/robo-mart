<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, RouterLink, RouterView } from 'vue-router'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import Menu from 'primevue/menu'
import Badge from 'primevue/badge'
import { useAdminAuthStore } from '@/stores/useAdminAuthStore'
import CommandPalette from '@/components/CommandPalette.vue'
import ConnectionStatus from '@/components/common/ConnectionStatus.vue'

const route = useRoute()
const adminAuthStore = useAdminAuthStore()

const isSidebarCollapsed = ref(false)
const userMenuRef = ref()
const commandPaletteRef = ref()
const notificationCount = ref(0)

const mainMarginLeft = computed(() => (isSidebarCollapsed.value ? '56px' : '240px'))
const topbarLeft = computed(() => (isSidebarCollapsed.value ? '56px' : '240px'))

const breadcrumbLabel = computed(() => {
  const name = route.name as string | undefined
  const map: Record<string, string> = {
    'admin-dashboard': 'Dashboard',
    'admin-products': 'Products',
    'admin-inventory': 'Inventory',
    'admin-orders': 'Orders',
    'admin-reports': 'Reports',
    'admin-system-events': 'Unprocessed Events',
  }
  return map[name ?? ''] ?? 'Admin'
})

const userMenuItems = [
  { label: 'Profile', icon: 'pi pi-user' },
  { separator: true },
  {
    label: 'Logout',
    icon: 'pi pi-sign-out',
    command: () => adminAuthStore.logout(),
  },
]
</script>

<template>
  <div class="admin-shell">
    <!-- Sidebar -->
    <aside class="admin-sidebar" :class="{ 'admin-sidebar--collapsed': isSidebarCollapsed }">
      <div class="admin-sidebar__logo">
        <span v-if="!isSidebarCollapsed">RoboMart Admin</span>
        <span v-else>RM</span>
      </div>

      <nav class="admin-sidebar__nav">
        <!-- Operations group -->
        <div class="admin-sidebar__section">
          <span v-if="!isSidebarCollapsed" class="admin-sidebar__section-label">Operations</span>
          <RouterLink to="/admin/dashboard" class="admin-nav-item">
            <i class="pi pi-home" />
            <span v-if="!isSidebarCollapsed">Dashboard</span>
          </RouterLink>
          <RouterLink to="/admin/orders" class="admin-nav-item">
            <i class="pi pi-shopping-cart" />
            <span v-if="!isSidebarCollapsed">Orders</span>
          </RouterLink>
          <RouterLink to="/admin/products" class="admin-nav-item">
            <i class="pi pi-box" />
            <span v-if="!isSidebarCollapsed">Products</span>
          </RouterLink>
          <RouterLink to="/admin/inventory" class="admin-nav-item">
            <i class="pi pi-warehouse" />
            <span v-if="!isSidebarCollapsed">Inventory</span>
          </RouterLink>
        </div>

        <!-- System group -->
        <div class="admin-sidebar__section">
          <span v-if="!isSidebarCollapsed" class="admin-sidebar__section-label">System</span>
          <a href="#" class="admin-nav-item">
            <i class="pi pi-heart" />
            <span v-if="!isSidebarCollapsed">Health</span>
          </a>
          <RouterLink to="/admin/system/events" class="admin-nav-item">
            <i class="pi pi-exclamation-triangle" />
            <span v-if="!isSidebarCollapsed">Unprocessed Events</span>
          </RouterLink>
          <RouterLink to="/admin/reports" class="admin-nav-item">
            <i class="pi pi-chart-bar" />
            <span v-if="!isSidebarCollapsed">Reports</span>
          </RouterLink>
        </div>
      </nav>

      <button
        class="admin-sidebar__toggle"
        :aria-label="isSidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
        @click="isSidebarCollapsed = !isSidebarCollapsed"
      >
        <i :class="isSidebarCollapsed ? 'pi pi-chevron-right' : 'pi pi-chevron-left'" />
      </button>
    </aside>

    <!-- Main wrapper -->
    <div class="admin-main-wrapper" :style="{ marginLeft: mainMarginLeft }">
      <!-- Top Header -->
      <header class="admin-topbar" :style="{ left: topbarLeft }">
        <div class="admin-topbar__breadcrumb">
          <span class="admin-breadcrumb-text">{{ breadcrumbLabel }}</span>
        </div>
        <div class="admin-topbar__actions">
          <ConnectionStatus />
          <button class="admin-cmd-btn" @click="commandPaletteRef?.open()">
            <i class="pi pi-search" />
            <span>⌘K</span>
          </button>
          <div class="admin-notification-btn">
            <Badge :value="notificationCount > 0 ? notificationCount : undefined" severity="danger">
              <i class="pi pi-bell" style="font-size: 1.1rem;" />
            </Badge>
          </div>
          <button class="admin-user-btn" @click="(e) => userMenuRef?.toggle(e)">
            <i class="pi pi-user-circle" />
            <span v-if="adminAuthStore.user">{{ adminAuthStore.user.username }}</span>
            <span v-else>Admin</span>
          </button>
          <Menu ref="userMenuRef" :model="userMenuItems" :popup="true" />
        </div>
      </header>

      <!-- Page Content -->
      <main id="admin-main-content" class="admin-content">
        <RouterView />
      </main>
    </div>

    <!-- Global components -->
    <ConfirmDialog />
    <Toast position="top-right" :maxToasts="3" />
    <CommandPalette ref="commandPaletteRef" />
  </div>
</template>

<style scoped>
.admin-shell {
  display: flex;
  min-height: 100vh;
}

.admin-sidebar {
  width: 240px;
  min-height: 100vh;
  background: #ffffff;
  border-right: 1px solid var(--color-gray-200);
  position: fixed;
  top: 0;
  left: 0;
  z-index: 100;
  transition: width 150ms ease;
  display: flex;
  flex-direction: column;
}

.admin-sidebar--collapsed {
  width: 56px;
}

.admin-sidebar__logo {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  font-weight: 600;
  font-size: 14px;
  color: var(--color-primary-700);
  border-bottom: 1px solid var(--color-gray-100);
  white-space: nowrap;
  overflow: hidden;
}

.admin-sidebar__nav {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
  overflow-x: hidden;
}

.admin-sidebar__section {
  margin-bottom: 8px;
}

.admin-sidebar__section-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-gray-400);
  padding: 8px 12px 4px;
  white-space: nowrap;
  overflow: hidden;
}

.admin-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 4px;
  color: var(--color-gray-700);
  text-decoration: none;
  font-size: 14px;
  transition: background 150ms ease;
  white-space: nowrap;
  overflow: hidden;
}

.admin-nav-item:hover {
  background: var(--color-primary-50);
  color: var(--color-primary-700);
}

.admin-nav-item.router-link-active {
  background: var(--color-primary-50);
  color: var(--color-primary-700);
  border-left: 3px solid var(--color-primary-700);
}

.admin-sidebar__toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  background: none;
  border: none;
  border-top: 1px solid var(--color-gray-100);
  cursor: pointer;
  color: var(--color-gray-500);
  transition: color 150ms ease;
}

.admin-sidebar__toggle:hover {
  color: var(--color-gray-700);
}

.admin-main-wrapper {
  flex: 1;
  transition: margin-left 150ms ease;
}

.admin-topbar {
  position: fixed;
  top: 0;
  right: 0;
  height: 48px;
  background: #ffffff;
  border-bottom: 1px solid var(--color-gray-200);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  z-index: 99;
  transition: left 150ms ease;
}

.admin-topbar__breadcrumb {
  display: flex;
  align-items: center;
}

.admin-breadcrumb-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-gray-800);
}

.admin-topbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-cmd-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--color-gray-100);
  border: 1px solid var(--color-gray-200);
  border-radius: 4px;
  font-size: 13px;
  color: var(--color-gray-600);
  cursor: pointer;
  transition: background 150ms ease;
}

.admin-cmd-btn:hover {
  background: var(--color-gray-200);
}

.admin-notification-btn {
  display: flex;
  align-items: center;
  padding: 4px 8px;
  cursor: pointer;
}

.admin-user-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: none;
  border: 1px solid var(--color-gray-200);
  border-radius: 4px;
  font-size: 13px;
  color: var(--color-gray-700);
  cursor: pointer;
  transition: background 150ms ease;
}

.admin-user-btn:hover {
  background: var(--color-gray-50);
}

.admin-content {
  margin-top: 48px;
  padding: 24px;
  min-height: calc(100vh - 48px);
}
</style>
