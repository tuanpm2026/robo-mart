<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="layout">
    <header class="header" role="banner">
      <div class="header__inner">
        <div class="header__logo">
          <RouterLink to="/" aria-label="RoboMart Home">
            <span class="header__logo-text">RoboMart</span>
          </RouterLink>
        </div>

        <div class="header__search">
          <div class="header__search-placeholder" aria-label="Search products">
            <svg
              width="20"
              height="20"
              viewBox="0 0 20 20"
              fill="none"
              aria-hidden="true"
              class="header__search-icon"
            >
              <circle cx="9" cy="9" r="6" stroke="currentColor" stroke-width="2" />
              <path d="M13.5 13.5L17 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
            <span>Search products...</span>
          </div>
        </div>

        <div class="header__actions">
          <button class="header__cart-btn" type="button" aria-label="Shopping cart, 0 items">
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
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
          </button>
          <button class="header__user-btn" type="button" aria-label="User menu">
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
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
          </button>
        </div>
      </div>
    </header>

    <nav class="category-nav" aria-label="Product categories">
      <div class="category-nav__inner">
        <span class="category-nav__placeholder">Categories will appear here</span>
      </div>
    </nav>

    <main id="main-content" class="main-content">
      <RouterView />
    </main>

    <footer class="footer" role="contentinfo">
      <div class="footer__inner">
        <p>&copy; {{ new Date().getFullYear() }} RoboMart. All rights reserved.</p>
      </div>
    </footer>
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

.header__search {
  flex: 1;
  max-width: 600px;
}

.header__search-placeholder {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  color: var(--color-gray-400);
  font-size: 14px;
  cursor: text;
}

.header__search-icon {
  flex-shrink: 0;
}

.header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.header__cart-btn,
.header__user-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  background: none;
  border: none;
  border-radius: 8px;
  color: var(--color-gray-600);
  cursor: pointer;
  transition: background-color 200ms, color 200ms;
}

.header__cart-btn:hover,
.header__user-btn:hover {
  background: var(--color-gray-100);
  color: var(--color-gray-900);
}

.category-nav {
  background: #ffffff;
  border-bottom: 1px solid var(--color-gray-200);
}

.category-nav__inner {
  display: flex;
  align-items: center;
  gap: 24px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 8px 24px;
}

.category-nav__placeholder {
  font-size: 14px;
  color: var(--color-gray-400);
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
  .header__user-btn {
    transition: none;
  }
}
</style>
