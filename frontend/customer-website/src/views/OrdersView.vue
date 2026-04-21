<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Skeleton from 'primevue/skeleton'
import Tag from 'primevue/tag'
import Paginator from 'primevue/paginator'
import { EmptyState } from '@robo-mart/shared'
import { useOrderStore } from '@/stores/useOrderStore'
import type { OrderStatus } from '@/types/order'

const orderStore = useOrderStore()
const router = useRouter()

const currentPage = ref(0)
const PAGE_SIZE = 10

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Order received',
  PAYMENT_PENDING: 'Awaiting payment',
  INVENTORY_RESERVING: 'Processing order',
  PAYMENT_PROCESSING: 'Processing payment',
  CONFIRMED: 'Order confirmed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  PAYMENT_REFUNDING: 'Cancelling',
  INVENTORY_RELEASING: 'Cancelling',
  CANCELLED: 'Cancelled',
}

const STATUS_SEVERITY: Record<OrderStatus, 'success' | 'warn' | 'danger' | 'info' | 'secondary'> = {
  PENDING: 'warn',
  PAYMENT_PENDING: 'warn',
  INVENTORY_RESERVING: 'warn',
  PAYMENT_PROCESSING: 'warn',
  CONFIRMED: 'success',
  SHIPPED: 'info',
  DELIVERED: 'info',
  PAYMENT_REFUNDING: 'warn',
  INVENTORY_RELEASING: 'warn',
  CANCELLED: 'danger',
}

function formatDate(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatPrice(amount: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

function onOrderClick(orderId: number) {
  router.push(`/orders/${orderId}`)
}

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  orderStore.fetchOrders(event.page, PAGE_SIZE)
}

function onBrowseProducts() {
  router.push('/')
}

onMounted(async () => {
  await orderStore.fetchOrders(0, PAGE_SIZE)
})
</script>

<template>
  <div class="orders">
    <h1 class="orders__title">My Orders</h1>

    <!-- Loading skeleton -->
    <div v-if="orderStore.isLoading" class="orders__skeleton">
      <div v-for="n in 3" :key="n" class="orders__skeleton-row">
        <Skeleton width="80px" height="1.2rem" />
        <Skeleton width="120px" height="1.2rem" />
        <Skeleton width="80px" height="1.2rem" />
        <Skeleton width="60px" height="1.2rem" />
        <Skeleton width="100px" height="1.8rem" border-radius="12px" />
      </div>
    </div>

    <!-- Error state -->
    <div v-else-if="orderStore.error" class="orders__error" role="alert">
      <p>{{ orderStore.error }}</p>
    </div>

    <!-- Empty state -->
    <EmptyState
      v-else-if="orderStore.orders.length === 0"
      variant="orders"
      @action="onBrowseProducts"
    />

    <!-- Order list -->
    <div v-else class="orders__content">
      <table class="orders__table" aria-label="My orders">
        <thead class="orders__thead">
          <tr>
            <th class="orders__th">Order</th>
            <th class="orders__th">Date</th>
            <th class="orders__th orders__th--right">Total</th>
            <th class="orders__th orders__th--right">Items</th>
            <th class="orders__th">Status</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="order in orderStore.orders"
            :key="order.id"
            class="orders__row"
            tabindex="0"
            :aria-label="`Order #${order.id}, ${formatDate(order.createdAt)}, ${formatPrice(order.totalAmount)}, ${STATUS_LABEL[order.status]}`"
            @click="onOrderClick(order.id)"
            @keydown.enter="onOrderClick(order.id)"
            @keydown.space.prevent="onOrderClick(order.id)"
          >
            <td class="orders__td orders__td--id">#{{ order.id }}</td>
            <td class="orders__td">{{ formatDate(order.createdAt) }}</td>
            <td class="orders__td orders__td--right">{{ formatPrice(order.totalAmount) }}</td>
            <td class="orders__td orders__td--right">
              {{ order.itemCount }} {{ order.itemCount === 1 ? 'item' : 'items' }}
            </td>
            <td class="orders__td">
              <Tag :value="STATUS_LABEL[order.status]" :severity="STATUS_SEVERITY[order.status]" />
            </td>
          </tr>
        </tbody>
      </table>

      <Paginator
        v-if="orderStore.pagination && orderStore.pagination.totalPages > 1"
        :rows="PAGE_SIZE"
        :total-records="orderStore.pagination.totalElements"
        :first="currentPage * PAGE_SIZE"
        class="orders__paginator"
        @page="onPageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.orders {
  padding: 24px 0;
}

.orders__title {
  font-size: 30px;
  font-weight: 700;
  color: var(--color-gray-900);
  margin: 0 0 24px;
}

.orders__skeleton {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.orders__skeleton-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  margin-bottom: 2px;
}

.orders__error {
  padding: 24px;
  text-align: center;
  color: var(--p-red-500, #ef4444);
}

.orders__content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.orders__table {
  width: 100%;
  border-collapse: collapse;
  background: #ffffff;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  overflow: hidden;
}

.orders__thead {
  background: var(--color-gray-50);
}

.orders__th {
  padding: 12px 16px;
  text-align: left;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-gray-600);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--color-gray-200);
}

.orders__th--right {
  text-align: right;
}

.orders__row {
  cursor: pointer;
  transition: background-color 150ms;
}

.orders__row:hover {
  background: var(--color-gray-50);
}

.orders__row:focus-visible {
  outline: 2px solid var(--p-primary-color, #2563eb);
  outline-offset: -2px;
}

.orders__td {
  padding: 16px;
  font-size: 14px;
  color: var(--color-gray-700);
  border-bottom: 1px solid var(--color-gray-100);
}

.orders__td--id {
  font-weight: 600;
  color: var(--color-primary-600);
}

.orders__td--right {
  text-align: right;
}

.orders__row:last-child .orders__td {
  border-bottom: none;
}

.orders__paginator {
  justify-content: center;
}

@media (prefers-reduced-motion: reduce) {
  .orders__row {
    transition: none;
  }
}
</style>
