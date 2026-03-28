import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface CategoryItem {
  id: number
  name: string
}

const SEED_CATEGORIES: CategoryItem[] = [
  { id: 1, name: 'Electronics' },
  { id: 2, name: 'Home & Garden' },
  { id: 3, name: 'Sports & Outdoors' },
  { id: 4, name: 'Health & Beauty' },
  { id: 5, name: 'Toys & Games' },
]

export const useCategoryStore = defineStore('category', () => {
  const categories = ref<CategoryItem[]>(SEED_CATEGORIES)
  const selectedCategoryId = ref<number | null>(null)

  function selectCategory(id: number | null) {
    selectedCategoryId.value = id
  }

  return {
    categories,
    selectedCategoryId,
    selectCategory,
  }
})
