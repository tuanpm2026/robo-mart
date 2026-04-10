<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import LiveOrderFeed from '@/components/dashboard/LiveOrderFeed.vue'
import MetricCard from '@/components/dashboard/MetricCard.vue'
import NeedsAttentionSection from '@/components/dashboard/NeedsAttentionSection.vue'
import { useWebSocket } from '@/composables/useWebSocket'
import { useDashboardStore } from '@/stores/useDashboardStore'
import { useInventoryStore } from '@/stores/useInventoryStore'
import { useOrderAdminStore } from '@/stores/useOrderAdminStore'

const { connect, disconnect } = useWebSocket()
const dashboardStore = useDashboardStore()
const inventoryStore = useInventoryStore()
const orderStore = useOrderAdminStore()

onMounted(async () => {
  connect()
  orderStore.pageSize = 5
  await Promise.all([
    dashboardStore.loadMetrics(),
    inventoryStore.loadInventory().catch(() => {}),
    orderStore.loadOrders(0).catch(() => {}),
  ])
})

onUnmounted(() => {
  disconnect()
})

function orderStatusSeverity(status: string): string {
  const map: Record<string, string> = {
    CONFIRMED: 'success',
    DELIVERED: 'success',
    PENDING: 'warn',
    PAYMENT_PENDING: 'warn',
    CANCELLED: 'danger',
  }
  return map[status] ?? 'secondary'
}
</script>

<template>
  <div class="dashboard">
    <h1 class="admin-page-title">Dashboard</h1>

    <Tabs value="business">
      <TabList>
        <Tab value="business">Business</Tab>
        <Tab value="system">System</Tab>
      </TabList>

      <TabPanels>
        <TabPanel value="business">
          <!-- Metric Cards -->
          <div class="metrics-grid">
            <MetricCard
              label="Orders Today"
              :value="dashboardStore.ordersToday"
              format="number"
              color="blue"
              :loading="dashboardStore.isLoading"
            />
            <MetricCard
              label="Revenue Today"
              :value="dashboardStore.revenueToday"
              format="currency"
              color="green"
              :loading="dashboardStore.isLoading"
            />
            <MetricCard
              label="Low Stock Items"
              :value="dashboardStore.lowStockCount"
              format="number"
              :color="dashboardStore.lowStockCount > 0 ? 'yellow' : 'green'"
              :loading="dashboardStore.isLoading"
            />
            <MetricCard
              label="System Health"
              :value="dashboardStore.systemHealth"
              format="label"
              :color="dashboardStore.systemHealth === 'healthy' ? 'green' : dashboardStore.systemHealth === 'degraded' ? 'yellow' : 'red'"
              :loading="dashboardStore.isLoading"
            />
          </div>

          <!-- Needs Attention -->
          <div class="section-gap">
            <NeedsAttentionSection />
          </div>

          <!-- Live Feed + Recent Orders -->
          <div class="two-col-layout section-gap">
            <section class="feed-panel">
              <LiveOrderFeed />
            </section>
            <section class="orders-panel">
              <h2 class="section-title">Recent Orders</h2>
              <DataTable
                :value="orderStore.orders"
                :loading="orderStore.isLoading"
                size="small"
              >
                <Column field="id" header="Order ID" />
                <Column field="createdAt" header="Date">
                  <template #body="{ data }">
                    {{ new Date(data.createdAt).toLocaleDateString() }}
                  </template>
                </Column>
                <Column field="totalAmount" header="Amount">
                  <template #body="{ data }">
                    {{ new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(data.totalAmount) }}
                  </template>
                </Column>
                <Column field="status" header="Status">
                  <template #body="{ data }">
                    <Tag :severity="orderStatusSeverity(data.status)" :value="data.status" />
                  </template>
                </Column>
              </DataTable>
            </section>
          </div>
        </TabPanel>

        <TabPanel value="system">
          <div class="system-placeholder">
            System health monitoring will be available in the next update.
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>

<style scoped>
.dashboard {
  height: 100%;
}

.admin-page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin-bottom: 16px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.section-gap {
  margin-bottom: 24px;
}

.two-col-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  height: calc(100vh - 420px);
  min-height: 300px;
}

.feed-panel,
.orders-panel {
  height: 100%;
  overflow: hidden;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-gray-900, #111827);
  margin: 0 0 12px;
}

.system-placeholder {
  padding: 48px 24px;
  text-align: center;
  color: var(--color-gray-500, #6b7280);
  font-size: 15px;
}
</style>
