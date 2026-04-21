<script setup lang="ts">
import { ref, watch } from 'vue'
import Checkbox from 'primevue/checkbox'
import Slider from 'primevue/slider'
import Rating from 'primevue/rating'
import Button from 'primevue/button'
import type { ProductSearchParams } from '@/types/product'

defineProps<{
  brands: string[]
}>()

const emit = defineEmits<{
  filter: [params: ProductSearchParams]
}>()

const selectedBrands = ref<string[]>([])
const priceRange = ref<number[]>([0, 1000])
const minRating = ref<number>(0)
const isCollapsed = ref(false)

function emitFilters() {
  const params: ProductSearchParams = {}
  if (selectedBrands.value.length > 0) {
    params.brand = selectedBrands.value[0]
  }
  if (priceRange.value[0]! > 0) {
    params.minPrice = priceRange.value[0]
  }
  if (priceRange.value[1]! < 1000) {
    params.maxPrice = priceRange.value[1]
  }
  if (minRating.value > 0) {
    params.minRating = minRating.value
  }
  emit('filter', params)
}

watch([selectedBrands, priceRange, minRating], emitFilters, { deep: true })

function clearAll() {
  selectedBrands.value = []
  priceRange.value = [0, 1000]
  minRating.value = 0
}

defineExpose({ clearAll })
</script>

<template>
  <aside class="filter-sidebar" aria-label="Search filters">
    <div class="filter-sidebar__header">
      <h2 class="filter-sidebar__title">Filters</h2>
      <Button
        :icon="isCollapsed ? 'pi pi-chevron-down' : 'pi pi-chevron-up'"
        text
        size="small"
        @click="isCollapsed = !isCollapsed"
        :aria-label="isCollapsed ? 'Expand filters' : 'Collapse filters'"
        :aria-expanded="!isCollapsed"
      />
    </div>

    <div v-show="!isCollapsed" class="filter-sidebar__body">
      <div v-if="brands.length > 0" class="filter-sidebar__section">
        <h3 class="filter-sidebar__section-title">Brand</h3>
        <div v-for="brand in brands" :key="brand" class="filter-sidebar__option">
          <Checkbox v-model="selectedBrands" :inputId="`brand-${brand}`" :value="brand" />
          <label :for="`brand-${brand}`" class="filter-sidebar__label">{{ brand }}</label>
        </div>
      </div>

      <div class="filter-sidebar__section">
        <h3 class="filter-sidebar__section-title">
          Price: ${{ priceRange[0] }} – ${{ priceRange[1] }}
        </h3>
        <Slider
          v-model="priceRange"
          range
          :min="0"
          :max="1000"
          :step="10"
          class="filter-sidebar__slider"
        />
      </div>

      <div class="filter-sidebar__section">
        <h3 class="filter-sidebar__section-title">Minimum Rating</h3>
        <Rating v-model="minRating" :cancel="true" />
      </div>

      <Button
        label="Clear Filters"
        severity="secondary"
        text
        size="small"
        @click="clearAll"
        class="filter-sidebar__clear"
      />
    </div>
  </aside>
</template>

<style scoped>
.filter-sidebar {
  width: 280px;
  flex-shrink: 0;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  background: #ffffff;
  padding: 16px;
}

.filter-sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.filter-sidebar__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0;
}

.filter-sidebar__body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.filter-sidebar__section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-sidebar__section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-gray-700);
  margin: 0;
}

.filter-sidebar__option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-sidebar__label {
  font-size: 14px;
  color: var(--color-gray-600);
  cursor: pointer;
}

.filter-sidebar__slider {
  width: 100%;
}

.filter-sidebar__clear {
  align-self: flex-start;
}
</style>
