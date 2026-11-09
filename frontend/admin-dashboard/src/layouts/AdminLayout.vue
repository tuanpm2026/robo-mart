<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import Drawer from 'primevue/drawer'
import { useMediaQuery } from '@robo-mart/shared'
import AdminNavigation from '@/components/AdminNavigation.vue'
import { useRoute, RouterView } from 'vue-router'
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
const isMobile = useMediaQuery('(max-width: 767px)')
const mobileNavigationOpen = ref(false)
const navigationToggle = ref<HTMLButtonElement | null>(null)
let navigationDestination = false
watch(isMobile, () => {
  mobileNavigationOpen.value = false
})
watch(
  () => route.fullPath,
  () => {
    mobileNavigationOpen.value = false
  },
)
function navigate() {
  navigationDestination = true
  mobileNavigationOpen.value = false
}
async function afterNavigationClose() {
  await nextTick()
  if (navigationDestination || !isMobile.value)
    document.getElementById('admin-main-content')?.focus()
  else navigationToggle.value?.focus()
  navigationDestination = false
}
const userMenuRef = ref()
const commandPaletteRef = ref()
const notificationCount = ref(0)

const mainMarginLeft = computed(() =>
  isMobile.value ? '0px' : isSidebarCollapsed.value ? '56px' : '240px',
)
const topbarLeft = computed(() =>
  isMobile.value ? '0px' : isSidebarCollapsed.value ? '56px' : '240px',
)

const breadcrumbLabel = computed(() => {
  const name = route.name as string | undefined
  const map: Record<string, string> = {
    'admin-dashboard': 'Dashboard',
    'admin-products': 'Products',
    'admin-inventory': 'Inventory',
    'admin-orders': 'Orders',
    'admin-reports': 'Reports',
    'admin-system-events': 'Unprocessed Events',
    'admin-system-health': 'System Health',
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
    <a href="#admin-main-content" class="skip-to-main">Skip to main content</a>
    <Drawer
      block-scroll
      v-model:visible="mobileNavigationOpen"
      id="admin-mobile-navigation"
      header="RoboMart Admin"
      aria-label="Administration navigation"
      position="left"
      :style="{ width: 'min(300px, calc(100vw - 32px))' }"
      @after-hide="afterNavigationClose"
    >
      <AdminNavigation @navigate="navigate" />
    </Drawer>
    <!-- Sidebar -->
    <aside
      v-if="!isMobile"
      class="admin-sidebar"
      :class="{ 'admin-sidebar--collapsed': isSidebarCollapsed }"
    >
      <div class="admin-sidebar__logo">
        <span v-if="!isSidebarCollapsed">RoboMart Admin</span>
        <span v-else>RM</span>
      </div>

      <AdminNavigation :collapsed="isSidebarCollapsed" />

      <button
        class="admin-sidebar__toggle"
        :aria-expanded="!isSidebarCollapsed"
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
          <button
            v-if="isMobile"
            ref="navigationToggle"
            type="button"
            class="admin-mobile-toggle"
            aria-label="Open navigation"
            aria-haspopup="dialog"
            :aria-controls="mobileNavigationOpen ? 'admin-mobile-navigation' : undefined"
            :aria-expanded="mobileNavigationOpen"
            @click="mobileNavigationOpen = true"
          >
            <i class="pi pi-bars" aria-hidden="true" />
          </button>
          <span class="admin-breadcrumb-text" :title="breadcrumbLabel">{{ breadcrumbLabel }}</span>
        </div>
        <div class="admin-topbar__actions">
          <ConnectionStatus />
          <button
            class="admin-cmd-btn"
            aria-label="Search administration commands"
            @click="commandPaletteRef?.open()"
          >
            <i class="pi pi-search" />
            <span>⌘K</span>
          </button>
          <div class="admin-notification-btn" role="img" aria-label="No new notifications">
            <Badge :value="notificationCount > 0 ? notificationCount : undefined" severity="danger">
              <i class="pi pi-bell" style="font-size: 1.1rem" />
            </Badge>
          </div>
          <button
            class="admin-user-btn"
            aria-label="Administrator account menu"
            @click="(e) => userMenuRef?.toggle(e)"
          >
            <i class="pi pi-user" />
            <span v-if="adminAuthStore.user">{{ adminAuthStore.user.username }}</span>
            <span v-else>Admin</span>
          </button>
          <Menu ref="userMenuRef" :model="userMenuItems" :popup="true" />
        </div>
      </header>

      <!-- Page Content -->
      <main id="admin-main-content" class="admin-content" tabindex="-1">
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

.admin-main-wrapper {
  min-width: 0;
}
.admin-sidebar {
  height: 100dvh;
  min-height: 0;
}
.admin-topbar__breadcrumb {
  min-width: 0;
  gap: 8px;
}
.admin-user-btn span {
  max-width: 130px;
  text-overflow: ellipsis;
  overflow: hidden;
  white-space: nowrap;
}
.admin-mobile-toggle {
  background: transparent;
  border: 1px solid var(--color-gray-200);
  border-radius: 4px;
  width: 44px;
  height: 44px;
  color: var(--color-gray-800);
}
@media (max-width: 767px) {
  .admin-topbar {
    height: 64px;
    padding: 0 12px;
    gap: 8px;
  }
  .admin-content {
    margin-top: 64px;
    padding: 16px;
  }
  .admin-user-btn {
    min-height: 44px;
  }
  .admin-user-btn span {
    display: none;
  }
  .admin-topbar__actions {
    gap: 4px;
  }
  .admin-cmd-btn {
    min-height: 44px;
  }
  .admin-cmd-btn span,
  .admin-notification-btn {
    display: none;
  }
}
.admin-topbar__breadcrumb {
  flex: 1;
}
.admin-breadcrumb-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-mobile-toggle,
.admin-topbar__actions {
  flex-shrink: 0;
}
</style>
