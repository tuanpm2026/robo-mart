<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AutoComplete from 'primevue/autocomplete'
import { searchProducts } from '@/api/productApi'
import type { ProductListItem } from '@/types/product'

const router = useRouter()
const query = ref('')
const suggestions = ref<ProductListItem[]>([])

async function onSearch(event: { query: string }) {
  if (event.query.length < 2) {
    suggestions.value = []
    return
  }
  try {
    const response = await searchProducts({ keyword: event.query, size: 5 })
    suggestions.value = response.data
  } catch {
    suggestions.value = []
  }
}

function onSelect(event: { value: ProductListItem }) {
  router.push(`/products/${event.value.id}`)
  query.value = ''
}

function onKeydownEnter() {
  const keyword = typeof query.value === 'string' ? query.value.trim() : ''
  if (keyword) {
    router.push({ path: '/search', query: { keyword } })
    query.value = ''
  }
}
</script>

<template>
  <div class="search-bar">
    <AutoComplete
      v-model="query"
      :suggestions="suggestions"
      optionLabel="name"
      placeholder="Search products..."
      :delay="200"
      :minLength="2"
      @complete="onSearch"
      @item-select="onSelect"
      @keydown.enter="onKeydownEnter"
      class="search-bar__input"
      inputClass="search-bar__field"
      aria-label="Search products"
    >
      <template #option="{ option }">
        <div class="search-bar__suggestion">
          <img
            :src="option.primaryImageUrl"
            :alt="option.name"
            class="search-bar__suggestion-img"
          />
          <div class="search-bar__suggestion-info">
            <span class="search-bar__suggestion-name">{{ option.name }}</span>
            <span class="search-bar__suggestion-price">${{ option.price.toFixed(2) }}</span>
          </div>
        </div>
      </template>
    </AutoComplete>
  </div>
</template>

<style scoped>
.search-bar {
  flex: 1;
  max-width: 600px;
}

.search-bar__input {
  width: 100%;
}

:deep(.search-bar__field) {
  width: 100%;
  padding: 10px 16px;
  font-size: 14px;
}

.search-bar__suggestion {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}

.search-bar__suggestion-img {
  width: 48px;
  height: 48px;
  border-radius: 4px;
  object-fit: cover;
  background: var(--color-gray-50);
}

.search-bar__suggestion-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.search-bar__suggestion-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-900);
}

.search-bar__suggestion-price {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-primary-600);
}
</style>
