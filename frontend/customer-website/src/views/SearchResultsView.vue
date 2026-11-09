<script setup lang="ts">
import { onMounted, watch, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import { EmptyState } from '@robo-mart/shared'
import ProductCard from '@/components/product/ProductCard.vue'
import ProductCardSkeleton from '@/components/product/ProductCardSkeleton.vue'
import FilterSidebar from '@/components/product/FilterSidebar.vue'
import { useProductStore } from '@/stores/useProductStore'
import type { ProductSearchParams } from '@/types/product'

const route = useRoute()
const productStore = useProductStore()
const filterSidebarRef = ref<InstanceType<typeof FilterSidebar> | null>(null)

const keyword = computed(() => (route.query.keyword as string) || '')

const activeFilterTags = computed(() => {
  const tags: { label: string; key: string }[] = []
  const f = productStore.filters
  if (f.brand) tags.push({ label: `Brand: ${f.brand}`, key: 'brand' })
  if (f.minPrice) tags.push({ label: `Min: $${f.minPrice}`, key: 'minPrice' })
  if (f.maxPrice && f.maxPrice < 1000) tags.push({ label: `Max: $${f.maxPrice}`, key: 'maxPrice' })
  if (f.minRating) tags.push({ label: `${f.minRating}+ stars`, key: 'minRating' })
  return tags
})

// Keep known choices mounted during a request so a focused checkbox survives.
// These are inferred choices for the current keyword, not server-wide facets.
const brands = ref<string[]>([])
watch(
  () => productStore.searchResults,
  (results) => {
    const found = results.map((product) => product.brand).filter(Boolean)
    if (found.length) brands.value = Array.from(new Set([...brands.value, ...found])).sort()
  },
  { immediate: true },
)

function performSearch(filterSnapshot: ProductSearchParams = productStore.filters) {
  const params: ProductSearchParams = {
    // Sidebar events are complete snapshots: omitted fields have been cleared.
    ...filterSnapshot,
    keyword: keyword.value || undefined,
  }
  productStore.fetchSearchResults(params)
}

onMounted(() => {
  performSearch()
})

watch(
  () => route.query.keyword,
  () => {
    brands.value = []
    clearAllFilters()
  },
)

function onFilterChange(params: ProductSearchParams) {
  performSearch(params)
}

function removeFilter(key: string) {
  filterSidebarRef.value?.clearFilter(key)
}

function clearAllFilters() {
  productStore.clearFilters()
  filterSidebarRef.value?.clearAll()
  performSearch()
}

function loadMore() {
  productStore.loadMoreSearchResults()
}
</script>

<template>
  <div class="search-results">
    <div class="search-results__header">
      <h1 class="search-results__title" v-if="keyword">
        {{ productStore.searchPagination?.totalElements ?? 0 }} results for "{{ keyword }}"
      </h1>
      <h1 class="search-results__title" v-else>All Products</h1>

      <div v-if="activeFilterTags.length > 0" class="search-results__filters">
        <Tag v-for="tag in activeFilterTags" :key="tag.key" :value="tag.label" severity="info">
          <span>{{ tag.label }}</span>
          <button
            type="button"
            class="search-results__remove-filter"
            :aria-label="`Remove ${tag.label} filter`"
            @click="removeFilter(tag.key)"
          >
            <span aria-hidden="true">×</span>
          </button>
        </Tag>
        <Button label="Clear all" text size="small" @click="clearAllFilters" />
      </div>
    </div>

    <div class="search-results__content">
      <FilterSidebar
        ref="filterSidebarRef"
        :brands="brands"
        :initial-filters="productStore.filters"
        @filter="onFilterChange"
      />

      <div class="search-results__main">
        <div
          v-if="productStore.isLoading && productStore.searchResults.length === 0"
          class="search-results__grid"
        >
          <ProductCardSkeleton v-for="n in 8" :key="n" />
        </div>

        <EmptyState
          v-else-if="productStore.searchResults.length === 0 && !productStore.isLoading"
          variant="search-results"
          @action="clearAllFilters"
        />

        <template v-else>
          <div class="search-results__grid">
            <ProductCard
              v-for="product in productStore.searchResults"
              :key="product.id"
              :product="product"
            />
          </div>

          <div v-if="productStore.hasMoreSearchResults" class="search-results__load-more">
            <Button
              label="Load more"
              severity="secondary"
              outlined
              :loading="productStore.isLoading"
              @click="loadMore"
            />
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-results {
  padding: 24px 0;
}

.search-results__header {
  margin-bottom: 24px;
}

.search-results__title {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-gray-900);
  margin: 0 0 12px;
}

.search-results__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.search-results__content {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.search-results__remove-filter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.search-results__main {
  flex: 1;
  min-width: 0;
}

.search-results__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.search-results__load-more {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

.search-results__grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.search-results__remove-filter {
  width: 32px;
  height: 32px;
}
@media (max-width: 1100px) {
  .search-results__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }
}
@media (max-width: 767px) {
  .search-results__content {
    flex-direction: column;
  }
  .search-results__main {
    width: 100%;
  }
}
@media (max-width: 380px) {
  .search-results__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
