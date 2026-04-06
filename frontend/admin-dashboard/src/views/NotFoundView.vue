<script setup lang="ts">
</script>

<template>
  <div class="not-found-page">
    <div class="not-found-card">
      <h1 class="not-found-title">Page Not Found</h1>
      <p class="not-found-message">The page you're looking for doesn't exist.</p>
      <RouterLink to="/admin/dashboard" class="not-found-btn">Back to Dashboard</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.not-found-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--color-gray-50);
}

.not-found-card {
  text-align: center;
  padding: 48px;
  background: white;
  border-radius: 8px;
  border: 1px solid var(--color-gray-200);
  max-width: 400px;
}

.not-found-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-gray-900);
  margin-bottom: 8px;
}

.not-found-message {
  font-size: 14px;
  color: var(--color-gray-600);
  margin-bottom: 24px;
}

.not-found-btn {
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

.not-found-btn:hover {
  background: var(--color-primary-800);
}
</style>
