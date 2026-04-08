<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useToast } from 'primevue/usetoast'
import DataTable, { type DataTablePageEvent } from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Select from 'primevue/select'
import MultiSelect from 'primevue/multiselect'
import Skeleton from 'primevue/skeleton'
import Timeline from 'primevue/timeline'
import { EmptyState } from '@robo-mart/shared'
import SlideOverPanel from '@/components/SlideOverPanel.vue'
import { useOrderAdminStore } from '@/stores/useOrderAdminStore'
import type { AdminOrderSummary } from '@/api/orderAdminApi'

const toast = useToast()
const orderStore = useOrderAdminStore()

const showDetailPanel = ref(false)
const updatingOrderIds = ref<Set<number>>(new Set())

const statusOptions = [
  { label: 'Pending', value: 'PENDING' },
  { label: 'Confirmed', value: 'CONFIRMED' },
  { label: 'Shipped', value: 'SHIPPED' },
  { label: 'Delivered', value: 'DELIVERED' },
  { label: 'Cancelled', value: 'CANCELLED' },
]

const statusSeverity: Record<string, string> = {
  PENDING: 'warn',
  INVENTORY_RESERVING: 'info',
  PAYMENT_PROCESSING: 'info',
  CONFIRMED: 'info',
  SHIPPED: 'info',
  DELIVERED: 'success',
  CANCELLED: 'danger',
  PAYMENT_REFUNDING: 'warn',
  INVENTORY_RELEASING: 'warn',
}

const statusLabels: Record<string, string> = {
  PENDING: 'Pending',
  INVENTORY_RESERVING: 'Reserving Items',
  PAYMENT_PROCESSING: 'Processing Payment',
  CONFIRMED: 'Confirmed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  PAYMENT_REFUNDING: 'Refunding Payment',
  INVENTORY_RELEASING: 'Releasing Inventory',
}

const adminTransitions: Record<string, { label: string; value: string }[]> = {
  CONFIRMED: [{ label: 'Shipped', value: 'SHIPPED' }],
  SHIPPED: [{ label: 'Delivered', value: 'DELIVERED' }],
}

const PAID_STATUSES = new Set(['CONFIRMED', 'SHIPPED', 'DELIVERED'])

function getPaymentStatus(status: string): string {
  if (PAID_STATUSES.has(status)) return 'Paid'
  if (status === 'CANCELLED') return 'Refunded / Cancelled'
  return 'Unpaid'
}

onMounted(() => orderStore.loadOrders())

watch(
  () => orderStore.statusFilter,
  () => orderStore.loadOrders(0),
  { deep: true },
)

function onPage(event: DataTablePageEvent) {
  orderStore.loadOrders(event.page)
}

async function onStatusChange(order: AdminOrderSummary, newStatus: string) {
  if (updatingOrderIds.value.has(order.id)) return
  updatingOrderIds.value.add(order.id)
  try {
    await orderStore.updateOrderStatus(order.id, newStatus)
    toast.add({
      severity: 'success',
      summary: 'Status updated',
      detail: `Order #${order.id} → ${statusLabels[newStatus] || newStatus}`,
      life: 3000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Update failed',
      detail: 'Could not update order status. Please try again.',
      life: 0,
    })
  } finally {
    updatingOrderIds.value.delete(order.id)
  }
}

async function openDetail(order: AdminOrderSummary) {
  try {
    await orderStore.loadOrderDetail(order.id)
    showDetailPanel.value = true
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Load failed',
      detail: 'Could not load order details.',
      life: 0,
    })
  }
}

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' })
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}
</script>

<template>
  <div class="orders-page">
    <!-- Page Header -->
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-semibold text-gray-900">Orders</h1>
      <MultiSelect
        v-model="orderStore.statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="Filter by status"
        :max-selected-labels="2"
        class="w-72"
        display="chip"
      />
    </div>

    <!-- Error State -->
    <div
      v-if="orderStore.error"
      class="mb-4 p-3 bg-red-50 text-red-700 rounded border border-red-200 text-sm"
    >
      {{ orderStore.error }}
      <Button label="Retry" text size="small" class="ml-2" @click="orderStore.loadOrders()" />
    </div>

    <!-- Orders DataTable -->
    <DataTable
      :value="orderStore.orders"
      :loading="orderStore.isLoading"
      :paginator="true"
      :rows="orderStore.pageSize"
      :total-records="orderStore.totalElements"
      :lazy="true"
      :rows-per-page-options="[10, 25, 50, 100]"
      sort-field="createdAt"
      :sort-order="-1"
      data-key="id"
      @page="onPage"
    >
      <template #loading>
        <div class="flex flex-col gap-2 p-4">
          <Skeleton v-for="i in 5" :key="i" height="40px" />
        </div>
      </template>
      <template #empty>
        <div class="p-6">
          <EmptyState
            variant="generic"
            title="No orders found"
            description="Orders will appear here once customers place them"
          />
        </div>
      </template>

      <Column field="id" header="Order #" sortable style="width: 100px">
        <template #body="{ data }">
          <span class="font-mono font-medium text-gray-900">#{{ data.id }}</span>
        </template>
      </Column>

      <Column field="userId" header="Customer" sortable>
        <template #body="{ data }">
          <span class="text-gray-700 text-sm">{{ data.userId }}</span>
        </template>
      </Column>

      <Column field="createdAt" header="Date" sortable style="width: 180px">
        <template #body="{ data }">
          <span class="text-gray-600 text-sm">{{ formatDate(data.createdAt) }}</span>
        </template>
      </Column>

      <Column field="itemCount" header="Items" style="width: 80px">
        <template #body="{ data }">
          <span class="text-gray-600">{{ data.itemCount }}</span>
        </template>
      </Column>

      <Column field="totalAmount" header="Total" sortable style="width: 120px">
        <template #body="{ data }">
          <span class="font-medium text-gray-900">{{ formatCurrency(data.totalAmount) }}</span>
        </template>
      </Column>

      <Column field="status" header="Status" style="width: 200px">
        <template #body="{ data }">
          <!-- Inline status dropdown for admin-editable statuses -->
          <Select
            v-if="adminTransitions[data.status]"
            :model-value="data.status"
            :options="[
              { label: statusLabels[data.status], value: data.status },
              ...adminTransitions[data.status],
            ]"
            option-label="label"
            option-value="value"
            class="w-40"
            :disabled="updatingOrderIds.has(data.id)"
            @update:model-value="(val: string) => onStatusChange(data, val)"
          />
          <!-- Static tag for non-editable statuses -->
          <Tag
            v-else
            :value="statusLabels[data.status] || data.status"
            :severity="(statusSeverity[data.status] as any) || 'info'"
          />
        </template>
      </Column>

      <Column header="Actions" style="width: 80px">
        <template #body="{ data }">
          <Button
            icon="pi pi-eye"
            text
            size="small"
            severity="info"
            title="View order details"
            @click="openDetail(data)"
          />
        </template>
      </Column>
    </DataTable>

    <!-- Order Detail Slide-Over -->
    <SlideOverPanel v-model:visible="showDetailPanel" :title="`Order #${orderStore.selectedOrder?.id ?? ''}`">
      <div v-if="orderStore.selectedOrder" class="flex flex-col gap-6">
        <!-- Order Info -->
        <div class="grid grid-cols-2 gap-4">
          <div>
            <span class="text-xs text-gray-500 uppercase tracking-wider">Status</span>
            <div class="mt-1">
              <Tag
                :value="statusLabels[orderStore.selectedOrder.status] || orderStore.selectedOrder.status"
                :severity="(statusSeverity[orderStore.selectedOrder.status] as any) || 'info'"
              />
            </div>
          </div>
          <div>
            <span class="text-xs text-gray-500 uppercase tracking-wider">Total</span>
            <p class="mt-1 font-semibold text-gray-900">
              {{ formatCurrency(orderStore.selectedOrder.totalAmount) }}
            </p>
          </div>
          <div>
            <span class="text-xs text-gray-500 uppercase tracking-wider">Customer</span>
            <p class="mt-1 text-sm text-gray-700">{{ orderStore.selectedOrder.userId }}</p>
          </div>
          <div>
            <span class="text-xs text-gray-500 uppercase tracking-wider">Date</span>
            <p class="mt-1 text-sm text-gray-700">
              {{ formatDate(orderStore.selectedOrder.createdAt) }}
            </p>
          </div>
          <div>
            <span class="text-xs text-gray-500 uppercase tracking-wider">Payment Status</span>
            <p class="mt-1 text-sm font-medium" :class="PAID_STATUSES.has(orderStore.selectedOrder.status) ? 'text-green-700' : 'text-gray-600'">
              {{ getPaymentStatus(orderStore.selectedOrder.status) }}
            </p>
          </div>
        </div>

        <!-- Shipping Address -->
        <div v-if="orderStore.selectedOrder.shippingAddress">
          <span class="text-xs text-gray-500 uppercase tracking-wider">Shipping Address</span>
          <p class="mt-1 text-sm text-gray-700">{{ orderStore.selectedOrder.shippingAddress }}</p>
        </div>

        <!-- Cancellation Reason -->
        <div v-if="orderStore.selectedOrder.cancellationReason">
          <span class="text-xs text-gray-500 uppercase tracking-wider">Cancellation Reason</span>
          <p class="mt-1 text-sm text-red-600">{{ orderStore.selectedOrder.cancellationReason }}</p>
        </div>

        <!-- Order Items -->
        <div>
          <h3 class="text-sm font-semibold text-gray-900 mb-2">Order Items</h3>
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-gray-500 border-b">
                <th class="pb-2">Product</th>
                <th class="pb-2 text-right">Qty</th>
                <th class="pb-2 text-right">Unit Price</th>
                <th class="pb-2 text-right">Subtotal</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in orderStore.selectedOrder.items"
                :key="item.productId"
                class="border-b border-gray-100"
              >
                <td class="py-2 text-gray-700">{{ item.productName }}</td>
                <td class="py-2 text-right text-gray-600">{{ item.quantity }}</td>
                <td class="py-2 text-right text-gray-600">{{ formatCurrency(item.unitPrice) }}</td>
                <td class="py-2 text-right font-medium text-gray-900">
                  {{ formatCurrency(item.subtotal) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Order Timeline -->
        <div>
          <h3 class="text-sm font-semibold text-gray-900 mb-2">Order Timeline</h3>
          <Timeline
            :value="orderStore.selectedOrder.statusHistory"
            class="order-timeline"
          >
            <template #content="{ item }">
              <div class="flex flex-col">
                <span
                  class="text-sm font-medium text-gray-800"
                  v-tooltip.top="item.status"
                >
                  {{ statusLabels[item.status] || item.status }}
                </span>
                <span class="text-xs text-gray-500">{{ formatDate(item.changedAt) }}</span>
              </div>
            </template>
          </Timeline>
        </div>
      </div>
    </SlideOverPanel>
  </div>
</template>

<style scoped>
.orders-page {
  padding: 0;
}

.order-timeline :deep(.p-timeline-event-opposite) {
  display: none;
}
</style>
