import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import {
  listInventory,
  restockItem as apiRestockItem,
  bulkRestock as apiBulkRestock,
  type InventoryItemEnriched,
} from '@/api/inventoryAdminApi'
import { listProducts } from '@/api/productAdminApi'

export const useInventoryStore = defineStore('inventory', () => {
  const items = ref<InventoryItemEnriched[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const currentPage = ref(0)
  const pageSize = ref(25)

  const lowStockItems = computed(() =>
    items.value.filter((i) => i.availableQuantity < i.lowStockThreshold),
  )

  async function loadInventory(page = 0) {
    isLoading.value = true
    error.value = null
    try {
      // Load inventory items and all products in parallel, join client-side by productId
      const [inventoryPage, productsPage] = await Promise.all([
        listInventory(page, pageSize.value),
        listProducts(0, 1000),
      ])

      const productMap = new Map(productsPage.data.map((p) => [p.id, p]))

      items.value = inventoryPage.data.map((item) => {
        const product = productMap.get(item.productId)
        return {
          ...item,
          productName: (product?.name ?? `Product #${item.productId}`) as string,
          sku: (product?.sku ?? '—') as string,
        } as unknown as InventoryItemEnriched
      })

      totalElements.value = inventoryPage.pagination.totalElements
      currentPage.value = page
    } catch (e) {
      error.value = 'Failed to load inventory data'
    } finally {
      isLoading.value = false
    }
  }

  async function restockItem(productId: number, quantity: number): Promise<void> {
    const updated = await apiRestockItem(productId, quantity, 'Admin restock')
    const idx = items.value.findIndex((i) => i.productId === productId)
    if (idx !== -1) {
      items.value[idx] = { ...items.value[idx]!, ...updated }
    }
  }

  async function bulkRestock(productIds: number[], quantity: number): Promise<void> {
    const updatedItems = await apiBulkRestock(productIds, quantity, 'Admin bulk restock')
    const updatedMap = new Map(updatedItems.map((i) => [i.productId, i]))
    items.value = items.value.map((i) =>
      updatedMap.has(i.productId) ? { ...i, ...updatedMap.get(i.productId)! } : i,
    )
  }

  return {
    items,
    isLoading,
    error,
    totalElements,
    currentPage,
    pageSize,
    lowStockItems,
    loadInventory,
    restockItem,
    bulkRestock,
  }
})
