<script setup lang="ts">
import { ref, watch } from 'vue'
import { useToast } from 'primevue/usetoast'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import InputNumber from 'primevue/inputnumber'
import Button from 'primevue/button'
import SlideOverPanel from '@/components/SlideOverPanel.vue'
import { createProduct, updateProduct, getCategories, type AdminProductListItem, type CategoryOption } from '@/api/productAdminApi'

const props = defineProps<{
  visible: boolean
  product: AdminProductListItem | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const toast = useToast()

const isVisible = ref(props.visible)
const isSaving = ref(false)
const categories = ref<CategoryOption[]>([])

// Form fields
const name = ref('')
const description = ref('')
const selectedCategoryId = ref<number | null>(null)
const price = ref<number | null>(null)
const brand = ref('')
const sku = ref('')

// Validation errors
const errors = ref({
  name: '',
  category: '',
  price: '',
})

watch(
  () => props.visible,
  (val) => {
    isVisible.value = val
    if (val) {
      resetForm()
      loadCategories()
    }
  },
)

watch(isVisible, (val) => {
  emit('update:visible', val)
})

function resetForm() {
  if (props.product) {
    // Edit mode — pre-populate
    name.value = props.product.name
    description.value = props.product.description ?? ''
    selectedCategoryId.value = props.product.categoryId
    price.value = props.product.price
    brand.value = props.product.brand ?? ''
    sku.value = props.product.sku
  } else {
    // Create mode — clear
    name.value = ''
    description.value = ''
    selectedCategoryId.value = null
    price.value = null
    brand.value = ''
    sku.value = ''
  }
  errors.value = { name: '', category: '', price: '' }
}

async function loadCategories() {
  try {
    categories.value = await getCategories()
  } catch {
    // Non-critical — user can still try to submit
  }
}

function validateField(field: keyof typeof errors.value) {
  if (field === 'name') {
    errors.value.name = name.value.trim() ? '' : 'Name is required'
  }
  if (field === 'category') {
    errors.value.category = selectedCategoryId.value ? '' : 'Category is required'
  }
  if (field === 'price') {
    errors.value.price = price.value && price.value > 0 ? '' : 'Price must be greater than 0'
  }
}

function validateAll(): boolean {
  validateField('name')
  validateField('category')
  validateField('price')
  return !errors.value.name && !errors.value.category && !errors.value.price
}

async function handleSubmit() {
  if (!validateAll()) return

  isSaving.value = true
  try {
    if (props.product) {
      // Edit mode
      await updateProduct(props.product.id, {
        name: name.value.trim(),
        description: description.value.trim(),
        categoryId: selectedCategoryId.value!,
        price: price.value!,
        brand: brand.value.trim(),
      })
      toast.add({ severity: 'success', summary: 'Product updated', life: 3000 })
    } else {
      // Create mode
      await createProduct({
        name: name.value.trim(),
        description: description.value.trim(),
        categoryId: selectedCategoryId.value!,
        price: price.value!,
        brand: brand.value.trim(),
        sku: sku.value.trim() || undefined,
      })
      toast.add({ severity: 'success', summary: 'Product created', life: 3000 })
    }
    emit('saved')
    isVisible.value = false
  } catch {
    toast.add({ severity: 'error', summary: 'Save failed', detail: 'Could not save product', life: 0 })
  } finally {
    isSaving.value = false
  }
}
</script>

<template>
  <SlideOverPanel
    v-model:visible="isVisible"
    :title="product ? 'Edit Product' : 'Add Product'"
  >
    <form class="product-form" @submit.prevent="handleSubmit">
      <!-- Two-column grid for admin efficiency -->
      <div class="form-grid">
        <!-- Name (full width) -->
        <div class="field col-span-2">
          <label class="field-label">Name <span class="text-error-500">*</span></label>
          <InputText
            v-model="name"
            class="w-full"
            placeholder="Product name"
            :class="{ 'p-invalid': errors.name }"
            @blur="validateField('name')"
          />
          <small v-if="errors.name" class="field-error">{{ errors.name }}</small>
        </div>

        <!-- Category (left) -->
        <div class="field">
          <label class="field-label">Category <span class="text-error-500">*</span></label>
          <Select
            v-model="selectedCategoryId"
            :options="categories"
            option-label="name"
            option-value="id"
            placeholder="Select category"
            class="w-full"
            :class="{ 'p-invalid': errors.category }"
            @blur="validateField('category')"
          />
          <small v-if="errors.category" class="field-error">{{ errors.category }}</small>
        </div>

        <!-- Price (right) -->
        <div class="field">
          <label class="field-label">Price <span class="text-error-500">*</span></label>
          <InputNumber
            v-model="price"
            mode="currency"
            currency="USD"
            :min="0.01"
            class="w-full"
            :class="{ 'p-invalid': errors.price }"
            @blur="validateField('price')"
          />
          <small v-if="errors.price" class="field-error">{{ errors.price }}</small>
        </div>

        <!-- Brand (left) -->
        <div class="field">
          <label class="field-label">Brand <span class="text-gray-400 text-xs">(optional)</span></label>
          <InputText v-model="brand" class="w-full" placeholder="Brand name" />
        </div>

        <!-- SKU (right, create mode only) -->
        <div v-if="!product" class="field">
          <label class="field-label">SKU <span class="text-gray-400 text-xs">(optional)</span></label>
          <InputText v-model="sku" class="w-full" placeholder="Auto-generated if empty" />
        </div>

        <!-- Description (full width) -->
        <div class="field col-span-2">
          <label class="field-label">Description <span class="text-gray-400 text-xs">(optional)</span></label>
          <Textarea v-model="description" :rows="3" class="w-full" placeholder="Product description" auto-resize />
        </div>
      </div>

      <!-- Form Actions -->
      <div class="form-actions">
        <Button
          type="submit"
          :label="isSaving ? 'Saving...' : (product ? 'Save Changes' : 'Create Product')"
          :icon="isSaving ? 'pi pi-spinner pi-spin' : 'pi pi-check'"
          :disabled="isSaving"
        />
        <Button
          label="Cancel"
          severity="secondary"
          outlined
          type="button"
          @click="isVisible = false"
        />
      </div>
    </form>
  </SlideOverPanel>
</template>

<style scoped>
.product-form {
  display: flex;
  flex-direction: column;
  gap: 0;
  height: 100%;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  flex: 1;
  padding: 4px 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.col-span-2 {
  grid-column: span 2;
}

.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-gray-700);
}

.field-error {
  color: var(--color-error-500);
  font-size: 12px;
}

.form-actions {
  display: flex;
  gap: 8px;
  padding-top: 20px;
  margin-top: 16px;
  border-top: 1px solid var(--color-gray-200);
}
</style>
