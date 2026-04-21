<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import { useToast } from 'primevue/usetoast'
import { useInventoryStore } from '@/stores/useInventoryStore'

const props = defineProps<{
  type: 'low-stock'
  productId: number
  productName: string
  currentStock: number
  threshold: number
}>()

const emit = defineEmits<{ dismissed: [] }>()

const router = useRouter()
const toast = useToast()
const inventoryStore = useInventoryStore()

const showRestockForm = ref(false)
const restockQty = ref(1)

function severity() {
  return props.currentStock === 0 ? 'danger' : 'warn'
}

function severityLabel() {
  return props.currentStock === 0 ? 'Critical' : 'Low Stock'
}

async function submitRestock() {
  try {
    await inventoryStore.restockItem(props.productId, restockQty.value)
    toast.add({ severity: 'success', summary: 'Stock updated', life: 3000 })
    emit('dismissed')
  } catch {
    toast.add({ severity: 'error', summary: 'Update failed', detail: 'Please try again', life: 0 })
  }
}
</script>

<template>
  <div class="alert-card">
    <div class="alert-card-header">
      <div class="alert-card-info">
        <Tag :severity="severity()" :value="severityLabel()" />
        <span class="product-name">{{ productName }}</span>
        <span class="stock-info">{{ currentStock }} / {{ threshold }} units</span>
      </div>
      <div class="alert-card-actions">
        <Button label="View" variant="text" size="small" @click="router.push('/admin/inventory')" />
        <Button label="Quick Restock" size="small" @click="showRestockForm = !showRestockForm" />
      </div>
    </div>
    <div v-if="showRestockForm" class="restock-form">
      <InputNumber v-model="restockQty" :min="1" :max="9999" />
      <Button label="Update" size="small" @click="submitRestock" />
      <Button label="Cancel" variant="text" size="small" @click="showRestockForm = false" />
    </div>
  </div>
</template>

<style scoped>
.alert-card {
  padding: 12px 16px;
  background: var(--color-gray-50, #f9fafb);
  border: 1px solid var(--color-gray-200, #e5e7eb);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.alert-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.alert-card-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.product-name {
  font-weight: 500;
  font-size: 14px;
  color: var(--color-gray-900, #111827);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stock-info {
  font-size: 13px;
  color: var(--color-gray-500, #6b7280);
  white-space: nowrap;
}

.alert-card-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.restock-form {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
}
</style>
