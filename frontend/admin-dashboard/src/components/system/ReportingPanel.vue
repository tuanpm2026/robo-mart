<script setup lang="ts">
import { ref, computed } from 'vue'
import Chart from 'primevue/chart'
import Button from 'primevue/button'
import SelectButton from 'primevue/selectbutton'
import Skeleton from 'primevue/skeleton'
import Calendar from 'primevue/calendar'
import { useReportsStore } from '@/stores/useReportsStore'

const store = useReportsStore()

const presetOptions = ['Today', '7D', '30D', 'Custom']
const selectedPreset = ref('Today')
const customRange = ref<Date[] | null>(null)

function startOfTodayUTC(): string {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  return d.toISOString()
}

function nowUTC(): string {
  return new Date().toISOString()
}

function daysAgoUTC(days: number): string {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString()
}

function onPresetChange(value: string) {
  selectedPreset.value = value
  if (value === 'Today') {
    store.setDateRange(startOfTodayUTC(), nowUTC())
  } else if (value === '7D') {
    store.setDateRange(daysAgoUTC(7), nowUTC())
  } else if (value === '30D') {
    store.setDateRange(daysAgoUTC(30), nowUTC())
  }
}

function onCustomRangeSelect(dates: Date[]) {
  if (dates && dates.length === 2 && dates[0] && dates[1]) {
    store.setDateRange(dates[0].toISOString(), dates[1].toISOString())
  }
}

const barData = computed(() => ({
  labels: store.summary?.topProducts.map((p) => p.productName) ?? [],
  datasets: [
    {
      label: 'Units Sold',
      data: store.summary?.topProducts.map((p) => p.totalQuantity) ?? [],
      backgroundColor: '#3b82f6',
    },
    {
      label: 'Revenue ($)',
      data: store.summary?.topProducts.map((p) => p.totalRevenue) ?? [],
      backgroundColor: '#22c55e',
    },
  ],
}))

const barOptions = {
  responsive: true,
  plugins: { legend: { position: 'top' } },
}

const doughnutData = computed(() => ({
  labels: store.summary?.revenueByProduct.map((p) => p.productName) ?? [],
  datasets: [
    {
      data: store.summary?.revenueByProduct.map((p) => p.totalRevenue) ?? [],
      backgroundColor: ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6'],
    },
  ],
}))

const lineData = computed(() => {
  const trends = store.summary?.orderTrends ?? []
  const dates = [...new Set(trends.map((t) => t.date))].sort()
  const statuses = [...new Set(trends.map((t) => t.status))]
  const statusColors: Record<string, string> = {
    CONFIRMED: '#22c55e',
    DELIVERED: '#16a34a',
    PENDING: '#f59e0b',
    PAYMENT_PENDING: '#f97316',
    CANCELLED: '#ef4444',
  }
  return {
    labels: dates,
    datasets: statuses.map((status) => ({
      label: status,
      data: dates.map((date) => {
        const entry = trends.find((t) => t.date === date && t.status === status)
        return entry?.count ?? 0
      }),
      borderColor: statusColors[status] ?? '#6b7280',
      fill: false,
    })),
  }
})

const lineOptions = {
  responsive: true,
  plugins: { legend: { position: 'top' } },
}
</script>

<template>
  <div class="reporting-panel">
    <div class="reporting-panel__header">
      <h2 class="reporting-panel__title">Reports</h2>
      <div class="reporting-panel__controls">
        <SelectButton
          :model-value="selectedPreset"
          :options="presetOptions"
          @update:model-value="onPresetChange"
        />
        <Calendar
          v-if="selectedPreset === 'Custom'"
          v-model="customRange"
          selection-mode="range"
          :show-icon="true"
          @update:model-value="onCustomRangeSelect"
        />
        <Button
          label="Rebuild Reports"
          icon="pi pi-refresh"
          :loading="store.isRebuilding"
          @click="store.rebuild()"
        />
      </div>
    </div>

    <div v-if="store.error" class="reporting-panel__error">
      {{ store.error }}
    </div>

    <div class="reporting-panel__charts">
      <!-- Top Products Bar Chart -->
      <div class="reporting-panel__chart-card">
        <h3>Top Selling Products</h3>
        <Skeleton v-if="store.isLoading" height="300px" />
        <Chart v-else type="bar" :data="barData" :options="barOptions" style="height: 300px" />
      </div>

      <!-- Revenue by Product Doughnut Chart -->
      <div class="reporting-panel__chart-card">
        <h3>Revenue by Product</h3>
        <Skeleton v-if="store.isLoading" height="300px" />
        <Chart v-else type="doughnut" :data="doughnutData" style="height: 300px" />
      </div>

      <!-- Order Trends Line Chart -->
      <div class="reporting-panel__chart-card reporting-panel__chart-card--wide">
        <h3>Order Trends</h3>
        <Skeleton v-if="store.isLoading" height="300px" />
        <Chart v-else type="line" :data="lineData" :options="lineOptions" style="height: 300px" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.reporting-panel {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.reporting-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.reporting-panel__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-800);
  margin: 0;
}

.reporting-panel__controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.reporting-panel__error {
  color: var(--color-error-600);
  padding: 12px;
  background: var(--color-error-50);
  border-radius: 4px;
}

.reporting-panel__charts {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 24px;
}

.reporting-panel__chart-card {
  background: #fff;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  padding: 20px;
}

.reporting-panel__chart-card h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-gray-700);
  margin: 0 0 16px;
}

.reporting-panel__chart-card--wide {
  grid-column: span 2;
}
</style>
