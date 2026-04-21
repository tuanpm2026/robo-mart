<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import { EmptyState } from '@robo-mart/shared'
import SlideOverPanel from './SlideOverPanel.vue'

interface Product {
  id: number
  name: string
  category: string
  price: number
  stock: number
  status: string
}

const products: Product[] = [
  { id: 1, name: 'RoboKit Pro', category: 'Robotics', price: 299.99, stock: 42, status: 'Active' },
  {
    id: 2,
    name: 'TechWheels X1',
    category: 'Components',
    price: 49.99,
    stock: 120,
    status: 'Active',
  },
  {
    id: 3,
    name: 'SensorPack v2',
    category: 'Sensors',
    price: 89.99,
    stock: 0,
    status: 'Out of Stock',
  },
  { id: 4, name: 'AI Brain Module', category: 'AI', price: 199.99, stock: 15, status: 'Low Stock' },
  { id: 5, name: 'PowerCell 5000', category: 'Power', price: 34.99, stock: 300, status: 'Active' },
]

const isLoading = ref(true)
const selectedRows = ref<Product[]>([])
const filters = ref({})
const slideOverVisible = ref(false)
const selectedProduct = ref<Product | null>(null)

let loadTimer: ReturnType<typeof setTimeout> | undefined

onMounted(() => {
  loadTimer = setTimeout(() => {
    isLoading.value = false
  }, 1000)
})

onUnmounted(() => {
  clearTimeout(loadTimer)
})

function viewProduct(product: Product) {
  selectedProduct.value = product
  slideOverVisible.value = true
}
</script>

<template>
  <div>
    <!-- Bulk action toolbar -->
    <div v-if="selectedRows.length > 0" class="bulk-toolbar">
      <span class="bulk-count">{{ selectedRows.length }} selected</span>
      <Button label="Delete Selected" severity="danger" size="small" icon="pi pi-trash" />
      <Button label="Export" severity="secondary" size="small" icon="pi pi-download" />
    </div>

    <DataTable
      :value="products"
      v-model:selection="selectedRows"
      v-model:filters="filters"
      filterDisplay="row"
      selectionMode="multiple"
      :loading="isLoading"
      loadingIcon="pi-spinner"
      :paginator="true"
      :rows="25"
      :rowsPerPageOptions="[10, 25, 50, 100]"
      dataKey="id"
    >
      <template #empty>
        <EmptyState variant="generic" />
      </template>

      <Column selectionMode="multiple" headerStyle="width: 3rem" />
      <Column field="id" header="ID" :sortable="true" />
      <Column field="name" header="Name" :sortable="true" />
      <Column field="category" header="Category" :sortable="true" />
      <Column field="price" header="Price" :sortable="true">
        <template #body="{ data }"> ${{ data.price.toFixed(2) }} </template>
      </Column>
      <Column field="stock" header="Stock" :sortable="true" />
      <Column field="status" header="Status" :sortable="true" />
      <Column header="Actions">
        <template #body="{ data }">
          <Button label="View" size="small" @click="viewProduct(data)" />
        </template>
      </Column>
    </DataTable>

    <SlideOverPanel v-model:visible="slideOverVisible" :title="selectedProduct?.name ?? 'Details'">
      <div v-if="selectedProduct" class="product-detail">
        <p><strong>ID:</strong> {{ selectedProduct.id }}</p>
        <p><strong>Category:</strong> {{ selectedProduct.category }}</p>
        <p><strong>Price:</strong> ${{ selectedProduct.price.toFixed(2) }}</p>
        <p><strong>Stock:</strong> {{ selectedProduct.stock }}</p>
        <p><strong>Status:</strong> {{ selectedProduct.status }}</p>
      </div>
    </SlideOverPanel>
  </div>
</template>

<style scoped>
.bulk-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--color-primary-50);
  border: 1px solid var(--color-primary-200);
  border-radius: 4px;
  margin-bottom: 8px;
}

.bulk-count {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-primary-700);
  flex: 1;
}

.product-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
  font-size: 14px;
}
</style>
