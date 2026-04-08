import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  listOrders,
  getOrderDetail,
  updateOrderStatus as apiUpdateOrderStatus,
  type AdminOrderSummary,
  type AdminOrderDetail,
} from '@/api/orderAdminApi'

export const useOrderAdminStore = defineStore('orderAdmin', () => {
  const orders = ref<AdminOrderSummary[]>([])
  const selectedOrder = ref<AdminOrderDetail | null>(null)
  const statusFilter = ref<string[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const currentPage = ref(0)
  const pageSize = ref(25)

  async function loadOrders(page = 0) {
    isLoading.value = true
    error.value = null
    try {
      const result = await listOrders(
        page,
        pageSize.value,
        statusFilter.value.length > 0 ? statusFilter.value : undefined,
      )
      orders.value = result.data
      totalElements.value = result.pagination.totalElements
      currentPage.value = page
    } catch {
      error.value = 'Failed to load orders'
    } finally {
      isLoading.value = false
    }
  }

  async function loadOrderDetail(orderId: number) {
    selectedOrder.value = null
    try {
      selectedOrder.value = await getOrderDetail(orderId)
    } catch {
      error.value = 'Failed to load order detail'
      throw new Error('Failed to load order detail')
    }
  }

  async function updateOrderStatus(orderId: number, status: string) {
    const updated = await apiUpdateOrderStatus(orderId, status)
    const idx = orders.value.findIndex((o) => o.id === orderId)
    if (idx !== -1) {
      orders.value[idx] = { ...orders.value[idx], ...updated }
    }
    return updated
  }

  return {
    orders,
    selectedOrder,
    statusFilter,
    isLoading,
    error,
    totalElements,
    currentPage,
    pageSize,
    loadOrders,
    loadOrderDetail,
    getOrderDetail: loadOrderDetail,
    updateOrderStatus,
  }
})
