import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getOrders as fetchOrdersApi,
  getOrder as fetchOrderApi,
  cancelOrder as cancelOrderApi,
} from '@/api/orderApi'
import type { OrderSummary, OrderDetail } from '@/types/order'
import type { PaginationMeta } from '@/types/product'

export const useOrderStore = defineStore('order', () => {
  const orders = ref<OrderSummary[]>([])
  const currentOrder = ref<OrderDetail | null>(null)
  const pagination = ref<PaginationMeta | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function fetchOrders(page = 0, size = 10) {
    isLoading.value = true
    error.value = null
    try {
      const response = await fetchOrdersApi({ page, size })
      orders.value = response.data
      pagination.value = response.pagination
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load orders'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchOrder(orderId: number) {
    isLoading.value = true
    error.value = null
    currentOrder.value = null
    try {
      const response = await fetchOrderApi(orderId)
      currentOrder.value = response.data
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load order'
    } finally {
      isLoading.value = false
    }
  }

  async function cancelOrder(orderId: number, reason?: string) {
    isLoading.value = true
    error.value = null
    try {
      await cancelOrderApi(orderId, reason)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to cancel order'
      isLoading.value = false
      throw err
    }
    // Cancel succeeded — refresh silently; refetch errors don't indicate cancel failure
    try {
      const response = await fetchOrderApi(orderId)
      currentOrder.value = response.data
    } finally {
      isLoading.value = false
    }
  }

  function $reset() {
    orders.value = []
    currentOrder.value = null
    pagination.value = null
    isLoading.value = false
    error.value = null
  }

  return {
    orders,
    currentOrder,
    pagination,
    isLoading,
    error,
    fetchOrders,
    fetchOrder,
    cancelOrder,
    $reset,
  }
})
