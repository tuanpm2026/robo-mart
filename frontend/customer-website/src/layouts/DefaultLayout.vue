<script setup lang="ts">
import { ref } from 'vue'
import { RouterView, RouterLink, useRoute, useRouter } from 'vue-router'
import Badge from 'primevue/badge'
import Menu from 'primevue/menu'
import { useToast } from 'primevue/usetoast'
import SearchBar from '@/components/product/SearchBar.vue'
import LoginModal from '@/components/auth/LoginModal.vue'
import DegradationBanner from '@/components/common/DegradationBanner.vue'
import { useCategoryStore } from '@/stores/useCategoryStore'
import { useCartStore } from '@/stores/useCartStore'
import { useAuthStore } from '@/stores/useAuthStore'

const categoryStore = useCategoryStore()
const cartStore = useCartStore()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const showLoginModal = ref(false)
const userMenu = ref()

const userMenuItems = ref([
  {
    label: 'My Account',
    icon: 'pi pi-user',
    disabled: true,
  },
  {
    label: 'My Orders',
    icon: 'pi pi-list',
    command: () => router.push('/orders'),
  },
  {
    separator: true,
  },
  {
    label: 'Logout',
    icon: 'pi pi-sign-out',
    command: handleLogout,
  },
])

function onCategoryClick(categoryId: number | null) {
  categoryStore.selectCategory(categoryId)
}

function isCategoryActive(categoryId: number | null): boolean {
  if (categoryId === null) {
    return !route.query.categoryId
  }
  return String(route.query.categoryId) === String(categoryId)
}

function onUserButtonClick(event: Event) {
  if (authStore.isAuthenticated) {
    userMenu.value.toggle(event)
  } else {
    showLoginModal.value = true
  }
}

async function handleLogout() {
  await authStore.logout()
  toast.add({
    severity: 'success',
    summary: 'Logged out',
    detail: 'You have been logged out successfully.',
    life: 3000,
  })
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="header__inner">
        <div class="header__logo">
          <RouterLink to="/" aria-label="RoboMart Home">
            <span class="header__logo-text">RoboMart</span>
          </RouterLink>
        </div>

        <SearchBar />

        <div class="header__actions">
          <RouterLink
            to="/cart"
            class="header__cart-btn"
            :aria-label="`Shopping cart, ${cartStore.totalItems} items`"
          >
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M6 2L3 6V20C3 20.5304 3.21071 21.0391 3.58579 21.4142C3.96086 21.7893 4.46957 22 5 22H19C19.5304 22 20.0391 21.7893 20.4142 21.4142C20.7893 21.0391 21 20.5304 21 20V6L18 2H6Z"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <path d="M3 6H21" stroke="currentColor" stroke-width="2" />
              <path
                d="M16 10C16 11.0609 15.5786 12.0783 14.8284 12.8284C14.0783 13.5786 13.0609 14 12 14C10.9391 14 9.92172 13.5786 9.17157 12.8284C8.42143 12.0783 8 11.0609 8 10"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <Badge
              v-if="cartStore.totalItems > 0"
              :value="cartStore.totalItems"
              class="header__cart-badge"
              aria-live="polite"
            />
          </RouterLink>

          <button
            class="header__user-btn"
            type="button"
            :aria-label="authStore.isAuthenticated ? 'User menu' : 'Log in'"
            @click="onUserButtonClick"
          >
            <template v-if="authStore.isAuthenticated">
              <span class="header__user-name">{{ authStore.displayName }}</span>
              <i class="pi pi-chevron-down header__user-chevron" />
            </template>
            <template v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M20 21V19C20 17.9391 19.5786 16.9217 18.8284 16.1716C18.0783 15.4214 17.0609 15 16 15H8C6.93913 15 5.92172 15.4214 5.17157 16.1716C4.42143 16.9217 4 17.9391 4 19V21"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <circle
                  cx="12"
                  cy="7"
                  r="4"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </template>
          </button>
          <Menu ref="userMenu" :model="userMenuItems" :popup="true" />
        </div>
      </div>
    </header>

    <nav class="category-nav" aria-label="Product categories">
      <div class="category-nav__inner">
        <RouterLink
          to="/"
          class="category-nav__link"
          :class="{ 'category-nav__link--active': isCategoryActive(null) }"
          @click="onCategoryClick(null)"
        >
          All
        </RouterLink>
        <RouterLink
          v-for="category in categoryStore.categories"
          :key="category.id"
          :to="{ path: '/', query: { categoryId: category.id } }"
          class="category-nav__link"
          :class="{ 'category-nav__link--active': isCategoryActive(category.id) }"
          @click="onCategoryClick(category.id)"
        >
          {{ category.name }}
        </RouterLink>
      </div>
    </nav>

    <DegradationBanner />

    <main id="main-content" class="main-content">
      <RouterView />
    </main>

    <footer class="footer">
      <div class="footer__inner">
        <p>&copy; {{ new Date().getFullYear() }} RoboMart. All rights reserved.</p>
      </div>
    </footer>

    <LoginModal v-model:visible="showLoginModal" />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #ffffff;
  border-bottom: 1px solid var(--color-gray-200);
}

.header__inner {
  display: flex;
  align-items: center;
  gap: 24px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 12px 24px;
}

.header__logo a {
  text-decoration: none;
  color: var(--color-primary-600);
}

.header__logo-text {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.5px;
}

.header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.header__cart-btn,
.header__user-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 40px;
  padding: 0 8px;
  background: none;
  border: none;
  border-radius: 8px;
  color: var(--color-gray-600);
  cursor: pointer;
  text-decoration: none;
  transition:
    background-color 200ms,
    color 200ms;
}

.header__cart-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  animation: badge-pop 200ms ease-out;
}

@keyframes badge-pop {
  0% {
    transform: scale(0.5);
  }
  70% {
    transform: scale(1.1);
  }
  100% {
    transform: scale(1);
  }
}

.header__cart-btn:hover,
.header__user-btn:hover {
  background: var(--color-gray-100);
  color: var(--color-gray-900);
}

.header__user-name {
  font-size: 14px;
  font-weight: 500;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header__user-chevron {
  font-size: 10px;
  margin-left: 4px;
}

.category-nav {
  background: #ffffff;
  border-bottom: 1px solid var(--color-gray-200);
}

.category-nav__inner {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 8px 24px;
  overflow-x: auto;
}

.category-nav__link {
  display: inline-flex;
  align-items: center;
  padding: 6px 16px;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-600);
  text-decoration: none;
  border-radius: 20px;
  white-space: nowrap;
  transition:
    background-color 200ms,
    color 200ms;
}

.category-nav__link:hover {
  background: var(--color-gray-100);
  color: var(--color-gray-900);
}

.category-nav__link--active {
  background: var(--color-primary-50);
  color: var(--color-primary-600);
  font-weight: 600;
}

.main-content {
  flex: 1;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
  padding: 24px;
}

.footer {
  background: var(--color-gray-50);
  border-top: 1px solid var(--color-gray-200);
}

.footer__inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--color-gray-500);
}

@media (prefers-reduced-motion: reduce) {
  .header__cart-btn,
  .header__user-btn,
  .category-nav__link {
    transition: none;
  }

  .header__cart-badge {
    animation: none;
  }
}
</style>
