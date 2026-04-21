<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useToast } from 'primevue/usetoast'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Tag from 'primevue/tag'
import Skeleton from 'primevue/skeleton'
import Dialog from 'primevue/dialog'
import { EmptyState } from '@robo-mart/shared'
import { useInventoryStore } from '@/stores/useInventoryStore'
import type { InventoryItemEnriched } from '@/api/inventoryAdminApi'

const toast = useToast()
const inventoryStore = useInventoryStore()

const selectedRows = ref<InventoryItemEnriched[]>([])
const showBulkRestockDialog = ref(false)
const bulkQuantity = ref<number>(1)
const isBulkRestocking = ref(false)

interface EditingCell {
  productId: number
  originalValue: number
  value: number
}
const editingCell = ref<EditingCell | null>(null)

onMounted(() => inventoryStore.loadInventory())

function isLowStock(item: InventoryItemEnriched): boolean {
  return item.availableQuantity < item.lowStockThreshold
}

function rowClass(item: InventoryItemEnriched): string {
  return isLowStock(item) ? 'bg-yellow-50' : ''
}

function startCellEdit(item: InventoryItemEnriched) {
  editingCell.value = {
    productId: item.productId,
    originalValue: item.availableQuantity,
    value: item.availableQuantity,
  }
}

async function saveCellEdit(item: InventoryItemEnriched) {
  if (!editingCell.value || editingCell.value.productId !== item.productId) return
  const cell = editingCell.value
  editingCell.value = null

  const delta = cell.value - cell.originalValue
  if (delta <= 0) {
    toast.add({
      severity: 'warn',
      summary: 'Invalid quantity',
      detail:
        'Restock quantity must be greater than current stock. To reduce stock, contact a system admin.',
      life: 5000,
    })
    return
  }

  try {
    await inventoryStore.restockItem(item.productId, delta)
    const updatedItem = inventoryStore.items.find((i) => i.productId === item.productId)
    toast.add({
      severity: 'success',
      summary: 'Stock updated',
      detail: `${item.productName} now has ${updatedItem?.availableQuantity ?? cell.value} units`,
      life: 3000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Update failed',
      detail: 'Could not update stock. Please try again.',
      life: 0,
    })
  }
}

function cancelCellEdit() {
  editingCell.value = null
}

function openBulkRestock() {
  bulkQuantity.value = 1
  showBulkRestockDialog.value = true
}

async function confirmBulkRestock() {
  if (!bulkQuantity.value || bulkQuantity.value < 1) return
  isBulkRestocking.value = true
  const productIds = selectedRows.value.map((r) => r.productId)
  const count = productIds.length
  const qty = bulkQuantity.value
  try {
    await inventoryStore.bulkRestock(productIds, qty)
    showBulkRestockDialog.value = false
    selectedRows.value = []
    toast.add({
      severity: 'success',
      summary: 'Bulk restock complete',
      detail: `Added ${qty} units to ${count} product(s)`,
      life: 3000,
    })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Bulk restock failed',
      detail: 'Bulk restock failed. No products were updated.',
      life: 0,
    })
  } finally {
    isBulkRestocking.value = false
  }
}
</script>

<template>
  <div class="inventory-page">
    <!-- Page Header -->
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-semibold text-gray-900">Inventory</h1>
      <span
        v-if="inventoryStore.lowStockItems.length > 0"
        class="text-sm text-yellow-700 font-medium"
      >
        ⚠ {{ inventoryStore.lowStockItems.length }} low-stock item(s)
      </span>
    </div>

    <!-- Error State -->
    <div
      v-if="inventoryStore.error"
      class="mb-4 p-3 bg-red-50 text-red-700 rounded border border-red-200 text-sm"
    >
      {{ inventoryStore.error }}
      <Button
        label="Retry"
        text
        size="small"
        class="ml-2"
        @click="inventoryStore.loadInventory()"
      />
    </div>

    <!-- Bulk Action Toolbar -->
    <div
      v-if="selectedRows.length > 0"
      class="flex items-center gap-2 mb-3 p-2 bg-primary-50 rounded border border-primary-200"
    >
      <span class="text-sm text-gray-700">{{ selectedRows.length }} selected</span>
      <Button
        label="Restock Selected"
        icon="pi pi-plus-circle"
        size="small"
        @click="openBulkRestock"
      />
      <Button label="Clear" text size="small" @click="selectedRows = []" />
    </div>

    <!-- Inventory DataTable -->
    <DataTable
      :value="inventoryStore.items"
      :loading="inventoryStore.isLoading"
      v-model:selection="selectedRows"
      selection-mode="multiple"
      :row-class="rowClass"
      :paginator="true"
      :rows="25"
      :rows-per-page-options="[10, 25, 50, 100]"
      sort-field="productName"
      :sort-order="1"
      data-key="productId"
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
            title="No inventory items"
            description="Inventory data will appear once products are configured"
          />
        </div>
      </template>

      <Column selection-mode="multiple" style="width: 40px" />

      <Column field="productName" header="Product Name" sortable>
        <template #body="{ data }">
          <span class="font-medium text-gray-900">{{ data.productName }}</span>
        </template>
      </Column>

      <Column field="sku" header="SKU" sortable>
        <template #body="{ data }">
          <span class="text-gray-500 text-sm font-mono">{{ data.sku }}</span>
        </template>
      </Column>

      <Column field="availableQuantity" header="Current Stock" sortable style="width: 160px">
        <template #body="{ data }">
          <!-- Inline edit mode -->
          <div v-if="editingCell?.productId === data.productId" class="flex items-center gap-1">
            <InputNumber
              v-model="editingCell!.value"
              :min="data.availableQuantity + 1"
              :use-grouping="false"
              input-class="w-20 text-sm"
              autofocus
              @keydown.enter="saveCellEdit(data)"
              @keydown.escape="cancelCellEdit"
            />
            <Button
              icon="pi pi-check"
              text
              size="small"
              severity="success"
              @click="saveCellEdit(data)"
            />
            <Button
              icon="pi pi-times"
              text
              size="small"
              severity="secondary"
              @click="cancelCellEdit"
            />
          </div>
          <!-- Display mode — click to edit -->
          <button
            v-else
            class="text-left w-full px-2 py-1 rounded hover:bg-gray-100 cursor-pointer transition-colors"
            :title="'Click to restock'"
            @click="startCellEdit(data)"
          >
            <span
              class="font-semibold"
              :class="isLowStock(data) ? 'text-yellow-700' : 'text-gray-900'"
            >
              {{ data.availableQuantity }}
            </span>
          </button>
        </template>
      </Column>

      <Column field="reservedQuantity" header="Reserved" sortable style="width: 100px">
        <template #body="{ data }">
          <span class="text-gray-600 text-sm">{{ data.reservedQuantity }}</span>
        </template>
      </Column>

      <Column field="totalQuantity" header="Total" sortable style="width: 100px">
        <template #body="{ data }">
          <span class="text-gray-600 text-sm">{{ data.totalQuantity }}</span>
        </template>
      </Column>

      <Column field="lowStockThreshold" header="Threshold" sortable style="width: 100px">
        <template #body="{ data }">
          <span class="text-gray-500 text-sm">{{ data.lowStockThreshold }}</span>
        </template>
      </Column>

      <Column header="Status" style="width: 120px">
        <template #body="{ data }">
          <Tag
            :severity="isLowStock(data) ? 'warn' : 'success'"
            :value="isLowStock(data) ? 'Low Stock' : 'In Stock'"
          />
        </template>
      </Column>
    </DataTable>

    <!-- Bulk Restock Dialog -->
    <Dialog
      v-model:visible="showBulkRestockDialog"
      header="Bulk Restock"
      modal
      style="width: 380px"
    >
      <div class="flex flex-col gap-3">
        <p class="text-sm text-gray-600">
          Add stock to <strong>{{ selectedRows.length }}</strong> selected product(s).
        </p>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium text-gray-700">Quantity to add</label>
          <InputNumber
            v-model="bulkQuantity"
            :min="1"
            :use-grouping="false"
            placeholder="Enter quantity"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          label="Cancel"
          severity="secondary"
          :disabled="isBulkRestocking"
          @click="showBulkRestockDialog = false"
        />
        <Button
          label="Restock"
          icon="pi pi-plus-circle"
          :loading="isBulkRestocking"
          :disabled="!bulkQuantity || bulkQuantity < 1"
          @click="confirmBulkRestock"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.inventory-page {
  padding: 0;
}

/* Low-stock row highlight — applied via :row-class */
:deep(.bg-yellow-50) {
  background-color: #fefce8 !important;
}
</style>
