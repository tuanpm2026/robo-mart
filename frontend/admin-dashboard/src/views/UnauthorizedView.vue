<script setup lang="ts"></script>

<template>
  <div class="unauthorized-page">
    <div class="unauthorized-card">
      <i class="pi pi-lock unauthorized-icon" />
      <h1 class="unauthorized-title">Access Denied</h1>
      <p class="unauthorized-message">You need admin permissions to view this page.</p>
      <a href="/" class="unauthorized-btn">Go to Login</a>
    </div>
  </div>
</template>

<style scoped>
.unauthorized-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--color-gray-50);
}

.unauthorized-card {
  text-align: center;
  padding: 48px;
  background: white;
  border-radius: 8px;
  border: 1px solid var(--color-gray-200);
  max-width: 400px;
}

.unauthorized-icon {
  font-size: 48px;
  color: var(--color-error-500);
  margin-bottom: 16px;
}

.unauthorized-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-gray-900);
  margin-bottom: 8px;
}

.unauthorized-message {
  font-size: 14px;
  color: var(--color-gray-600);
  margin-bottom: 24px;
}

.unauthorized-btn {
  display: inline-block;
  padding: 8px 24px;
  background: var(--color-primary-700);
  color: white;
  border-radius: 4px;
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: background 150ms ease;
}

.unauthorized-btn:hover {
  background: var(--color-primary-800);
}
</style>
