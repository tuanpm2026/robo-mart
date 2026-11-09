<script setup lang="ts">
import { RouterLink } from 'vue-router'
defineProps<{ collapsed?: boolean }>()
const emit = defineEmits<{ navigate: [] }>()
</script>
<template>
  <nav class="admin-sidebar__nav" aria-label="Administration">
    <!-- Operations group -->
    <div class="admin-sidebar__section">
      <span v-if="!collapsed" class="admin-sidebar__section-label">Operations</span>
      <RouterLink
        to="/admin/dashboard"
        class="admin-nav-item"
        aria-label="Dashboard"
        @click="emit('navigate')"
      >
        <i class="pi pi-home" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Dashboard</span
        >
      </RouterLink>
      <RouterLink
        to="/admin/orders"
        class="admin-nav-item"
        aria-label="Orders"
        @click="emit('navigate')"
      >
        <i class="pi pi-shopping-cart" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Orders</span
        >
      </RouterLink>
      <RouterLink
        to="/admin/products"
        class="admin-nav-item"
        aria-label="Products"
        @click="emit('navigate')"
      >
        <i class="pi pi-box" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Products</span
        >
      </RouterLink>
      <RouterLink
        to="/admin/inventory"
        class="admin-nav-item"
        aria-label="Inventory"
        @click="emit('navigate')"
      >
        <i class="pi pi-warehouse" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Inventory</span
        >
      </RouterLink>
    </div>

    <!-- System group -->
    <div class="admin-sidebar__section">
      <span v-if="!collapsed" class="admin-sidebar__section-label">System</span>
      <RouterLink
        to="/admin/system/health"
        class="admin-nav-item"
        aria-label="Health"
        @click="emit('navigate')"
      >
        <i class="pi pi-heart" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Health</span
        >
      </RouterLink>
      <RouterLink
        to="/admin/system/events"
        class="admin-nav-item"
        aria-label="Unprocessed Events"
        @click="emit('navigate')"
      >
        <i class="pi pi-exclamation-triangle" aria-hidden="true" /><span
          :class="{ 'sr-only': collapsed }"
          >Unprocessed Events</span
        >
      </RouterLink>
      <RouterLink
        to="/admin/reports"
        class="admin-nav-item"
        aria-label="Reports"
        @click="emit('navigate')"
      >
        <i class="pi pi-chart-bar" aria-hidden="true" /><span :class="{ 'sr-only': collapsed }"
          >Reports</span
        >
      </RouterLink>
    </div>
  </nav>
</template>
<style scoped>
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
  color: var(--color-gray-600);
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

.admin-nav-item {
  min-height: 44px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  clip-path: inset(50%);
  overflow: hidden;
  white-space: nowrap;
}
</style>
