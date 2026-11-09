<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Tag from 'primevue/tag'
import Skeleton from 'primevue/skeleton'
import { EmptyState } from '@robo-mart/shared'
import ProductFormSlideOver from '@/components/products/ProductFormSlideOver.vue'
import {
  listProducts,
  deleteProduct,
  updateProduct,
  type AdminProductListItem,
  type UpdateProductPayload,
} from '@/api/productAdminApi'

const toast = useToast()
const confirm = useConfirm()

const products = ref<AdminProductListItem[]>([])
const isLoading = ref(true)
const loadError = ref(false)
const selectedRows = ref<AdminProductListItem[]>([])
const filters = ref({ name: { value: null, matchMode: 'contains' } })
const showForm = ref(false)
const editingProduct = ref<AdminProductListItem | null>(null)

interface EditingCell {
  id: number
  field: 'price'
  value: number
}
const editingCell = ref<EditingCell | null>(null)
const isDeleting = ref(false)

async function fetchProducts() {
  isLoading.value = true
  loadError.value = false
  try {
    const response = await listProducts(0, 100)
    products.value = response.data
  } catch {
    loadError.value = true
  } finally {
    isLoading.value = false
  }
}

onMounted(fetchProducts)

function openCreate() {
  editingProduct.value = null
  showForm.value = true
}

function openEdit(row: AdminProductListItem) {
  editingProduct.value = row
  showForm.value = true
}

async function onFormSaved() {
  await fetchProducts()
}

async function startCellEdit(row: AdminProductListItem, field: 'price') {
  editingCell.value = { id: row.id, field, value: row[field] }
  await nextTick()
  document.getElementById(`product-price-${row.id}`)?.focus()
}

async function returnToPriceAction(id: number) {
  await nextTick()
  document.getElementById(`edit-price-${id}`)?.focus()
}

async function saveCellEdit(row: AdminProductListItem) {
  if (!editingCell.value) return
  const cell = editingCell.value
  editingCell.value = null
  returnToPriceAction(row.id)

  const payload: UpdateProductPayload = {
    name: row.name,
    description: row.description ?? '',
    categoryId: row.categoryId,
    price: cell.value,
    brand: row.brand ?? '',
  }

  try {
    await updateProduct(row.id, payload)
    const idx = products.value.findIndex((p) => p.id === row.id)
    if (idx !== -1) {
      products.value[idx] = { ...products.value[idx]!, price: cell.value }
    }
    toast.add({ severity: 'success', summary: 'Product updated', life: 3000 })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Update failed',
      detail: 'Could not save changes',
      life: 0,
    })
  }
}

function cancelCellEdit() {
  const id = editingCell.value?.id
  editingCell.value = null
  if (id !== undefined) returnToPriceAction(id)
}

function confirmDelete(row: AdminProductListItem) {
  confirm.require({
    message: `Delete "${row.name}"? This cannot be undone.`,
    header: 'Confirm Delete',
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: () => handleDelete(row),
  })
}

async function handleDelete(row: AdminProductListItem) {
  try {
    await deleteProduct(row.id)
    products.value = products.value.filter((p) => p.id !== row.id)
    toast.add({ severity: 'success', summary: 'Product deleted', life: 3000 })
  } catch {
    toast.add({
      severity: 'error',
      summary: 'Delete failed',
      detail: 'Could not delete product',
      life: 0,
    })
  }
}

function deleteSelected() {
  if (selectedRows.value.length === 0) return
  const toDelete = [...selectedRows.value]
  confirm.require({
    message: `Delete ${toDelete.length} selected product(s)? This cannot be undone.`,
    header: 'Confirm Bulk Delete',
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: () => executeBulkDelete(toDelete),
  })
}

async function executeBulkDelete(toDelete: typeof selectedRows.value) {
  isDeleting.value = true
  selectedRows.value = []
  let successCount = 0
  let failCount = 0
  for (const row of toDelete) {
    try {
      await deleteProduct(row.id)
      products.value = products.value.filter((p) => p.id !== row.id)
      successCount++
    } catch {
      failCount++
    }
  }
  isDeleting.value = false
  if (failCount === 0) {
    toast.add({ severity: 'success', summary: `${successCount} product(s) deleted`, life: 3000 })
  } else {
    toast.add({
      severity: 'warn',
      summary: `${successCount} deleted, ${failCount} failed`,
      detail: 'Some products could not be deleted',
      life: 5000,
    })
  }
}
</script>

<template>
  <div class="products-page">
    <!-- Page Header -->
    <div class="page-heading flex items-center justify-between mb-4">
      <h1 class="text-xl font-semibold text-gray-900">Products</h1>
      <Button label="Add Product" icon="pi pi-plus" @click="openCreate" />
    </div>

    <div v-if="loadError" role="alert" class="mb-4 p-3 bg-red-50 text-red-700 rounded">
      Could not load products. <Button label="Retry" text @click="fetchProducts" />
    </div>

    <!-- Bulk Action Toolbar -->
    <div
      v-if="selectedRows.length > 0"
      class="flex items-center gap-2 mb-3 p-2 bg-primary-50 rounded border border-primary-200"
    >
      <span class="text-sm text-gray-700">{{ selectedRows.length }} selected</span>
      <Button
        label="Delete Selected"
        icon="pi pi-trash"
        severity="danger"
        size="small"
        :disabled="isDeleting"
        @click="deleteSelected"
      />
      <Button label="Clear" text size="small" @click="selectedRows = []" />
    </div>

    <!-- Products DataTable -->
    <DataTable
      :table-style="{ minWidth: '64rem' }"
      :pt="{
        tableContainer: { tabindex: 0, role: 'region', 'aria-label': 'Scrollable products table' },
      }"
      :value="products"
      :loading="isLoading"
      v-model:selection="selectedRows"
      v-model:filters="filters"
      selection-mode="multiple"
      :paginator="true"
      :rows="25"
      :rows-per-page-options="[10, 25, 50, 100]"
      filter-display="row"
      sort-field="id"
      :sort-order="-1"
      data-key="id"
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
            title="No products yet"
            description="Start building your catalog"
            cta-label="Add First Product"
            @action="openCreate"
          />
        </div>
      </template>

      <Column selection-mode="multiple" style="width: 40px" />

      <Column field="id" header="ID" sortable style="width: 80px">
        <template #body="{ data }">
          <span class="text-gray-500 text-sm">{{ data.id }}</span>
        </template>
      </Column>

      <Column field="name" header="Name" sortable :show-filter-menu="false">
        <template #filter="{ filterModel, filterCallback }">
          <input
            v-model="filterModel.value"
            type="text"
            placeholder="Search name"
            aria-label="Filter products by name"
            class="p-inputtext p-component w-full text-sm"
            @input="filterCallback()"
          />
        </template>
        <template #body="{ data }">
          <span class="font-medium text-gray-900">{{ data.name }}</span>
          <div class="text-xs text-gray-600">{{ data.sku }}</div>
        </template>
      </Column>

      <Column field="categoryName" header="Category" sortable>
        <template #body="{ data }">
          <Tag :value="data.categoryName" severity="secondary" />
        </template>
      </Column>

      <Column field="price" header="Price" sortable style="width: 140px">
        <template #body="{ data }">
          <button
            type="button"
            v-if="!editingCell || editingCell.id !== data.id || editingCell.field !== 'price'"
            class="cursor-pointer hover:text-primary-700 font-mono"
            title="Edit price"
            :id="`edit-price-${data.id}`"
            :aria-label="`Edit price for ${data.name}`"
            @click="startCellEdit(data, 'price')"
          >
            ${{ Number(data.price).toFixed(2) }}
          </button>
          <InputNumber
            v-else
            :model-value="editingCell.value"
            :inputId="`product-price-${data.id}`"
            :aria-label="`Price for ${data.name}`"
            mode="currency"
            currency="USD"
            :min="0.01"
            :input-style="{ width: '110px' }"
            autofocus
            @update:model-value="
              (v) => {
                if (editingCell) editingCell.value = v ?? 0
              }
            "
            @keydown.enter="saveCellEdit(data)"
            @keydown.escape="cancelCellEdit"
          />
        </template>
      </Column>

      <Column field="stockQuantity" header="Stock" sortable style="width: 80px">
        <template #body="{ data }">
          <Tag
            :value="String(data.stockQuantity)"
            :severity="data.stockQuantity > 0 ? 'success' : 'danger'"
          />
        </template>
      </Column>

      <Column
        header="Actions"
        frozen
        align-frozen="right"
        header-class="row-actions"
        body-class="row-actions"
        style="width: 120px"
      >
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              title="Edit"
              :aria-label="`Edit ${data.name}`"
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              title="Delete"
              :aria-label="`Delete ${data.name}`"
              @click="confirmDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- Product Form Slide-Over -->
    <ProductFormSlideOver
      v-model:visible="showForm"
      :product="editingProduct"
      @saved="onFormSaved"
    />
  </div>
</template>

<style scoped>
.products-page {
  padding: 0;
}

.page-heading {
  flex-wrap: wrap;
  gap: 12px;
}
.page-heading :deep(.p-multiselect) {
  max-width: 100%;
}
:deep(.p-datatable) {
  max-width: 100%;
  min-width: 0;
}
:deep(.p-datatable-table-container) {
  overflow-x: auto;
  max-width: 100%;
}
:deep(td) {
  overflow-wrap: anywhere;
}

:deep(.row-actions) {
  position: sticky;
  right: 0;
  background: var(--color-gray-50);
  z-index: 1;
  box-shadow: -2px 0 4px rgb(0 0 0 / 8%);
}
</style>
