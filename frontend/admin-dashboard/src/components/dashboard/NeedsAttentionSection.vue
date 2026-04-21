<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Skeleton from 'primevue/skeleton'
import AlertCard from './AlertCard.vue'
import { useInventoryStore } from '@/stores/useInventoryStore'

const inventoryStore = useInventoryStore()

const hasLoaded = ref(false)
watch(
  () => inventoryStore.isLoading,
  (loading) => {
    if (!loading) hasLoaded.value = true
  },
)

const dismissedIds = ref<Set<number>>(new Set())

const visibleAlerts = computed(() =>
  inventoryStore.lowStockItems
    .filter((item) => !dismissedIds.value.has(item.productId))
    .sort((a, b) => {
      if (a.availableQuantity === 0 && b.availableQuantity !== 0) return -1
      if (b.availableQuantity === 0 && a.availableQuantity !== 0) return 1
      return a.availableQuantity / a.lowStockThreshold - b.availableQuantity / b.lowStockThreshold
    }),
)

function handleDismissed(productId: number) {
  dismissedIds.value = new Set([...dismissedIds.value, productId])
}
</script>

<template>
  <div class="needs-attention">
    <h2 class="section-title">Needs Attention</h2>

    <div v-if="inventoryStore.isLoading || !hasLoaded" class="skeleton-list">
      <Skeleton height="56px" class="skeleton-item" />
      <Skeleton height="56px" class="skeleton-item" />
    </div>

    <div v-else-if="visibleAlerts.length === 0" class="empty-state">
      <span class="checkmark">✓</span>
      <span>All clear — no low-stock items</span>
    </div>

    <div v-else class="alerts-list">
      <AlertCard
        v-for="item in visibleAlerts"
        :key="item.productId"
        type="low-stock"
        :productId="item.productId"
        :productName="item.productName"
        :currentStock="item.availableQuantity"
        :threshold="item.lowStockThreshold"
        @dismissed="handleDismissed(item.productId)"
      />
    </div>
  </div>
</template>

<style scoped>
.needs-attention {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0;
}

.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton-item {
  border-radius: 8px;
}

.empty-state {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  color: var(--color-gray-500, #6b7280);
  font-size: 14px;
}

.checkmark {
  color: #22c55e;
  font-size: 18px;
  font-weight: 700;
}

.alerts-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
