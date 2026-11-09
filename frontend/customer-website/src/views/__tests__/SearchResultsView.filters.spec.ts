import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Checkbox from 'primevue/checkbox'
import Slider from 'primevue/slider'
import Rating from 'primevue/rating'
import FilterSidebar from '@/components/product/FilterSidebar.vue'
import { searchProducts } from '@/api/productApi'
import { useProductStore } from '@/stores/useProductStore'
import type { ProductSearchParams } from '@/types/product'
import SearchResultsView from '../SearchResultsView.vue'

vi.mock('@/api/productApi', () => ({ searchProducts: vi.fn() }))

const wrappers: ReturnType<typeof mount>[] = []

async function renderSearch(filters?: ProductSearchParams, attachTo?: HTMLElement) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/search', component: SearchResultsView }],
  })
  const pinia = createPinia()
  if (filters) useProductStore(pinia).filters = filters
  await router.push('/search?keyword=mouse')
  await router.isReady()
  const wrapper = mount(SearchResultsView, {
    ...(attachTo ? { attachTo } : {}),
    global: { plugins: [router, pinia, PrimeVue, ToastService] },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

function lastRequest() {
  return vi.mocked(searchProducts).mock.lastCall?.[0]
}

function removalButton(wrapper: ReturnType<typeof mount>, label: string) {
  const button = wrapper
    .findAll('button')
    .find((element) => element.attributes('aria-label') === `Remove ${label} filter`)
  expect(button).toBeDefined()
  return button!
}

describe('SearchResultsView complete filter snapshots', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(searchProducts).mockResolvedValue({
      data: [
        {
          id: 1,
          sku: 'P1',
          name: 'Mouse',
          price: 29,
          rating: 4,
          brand: 'Logitech',
          stockQuantity: 10,
          categoryName: 'Electronics',
          primaryImageUrl: '/fixture.png',
        },
      ],
      pagination: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
      traceId: 'filter-regression',
    })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('removes an unchecked brand from API requests while retaining selected price and rating', async () => {
    const { wrapper } = await renderSearch()
    const sidebar = wrapper.findComponent(FilterSidebar)
    sidebar.findComponent(Checkbox).vm.$emit('update:modelValue', ['Logitech'])
    sidebar.findComponent(Slider).vm.$emit('update:modelValue', [10, 200])
    sidebar.findComponent(Rating).vm.$emit('update:modelValue', 4)
    await flushPromises()
    expect(lastRequest()).toEqual({
      keyword: 'mouse',
      brand: 'Logitech',
      minPrice: 10,
      maxPrice: 200,
      minRating: 4,
      page: 0,
      size: 20,
    })

    sidebar.findComponent(Checkbox).vm.$emit('update:modelValue', [])
    await flushPromises()
    expect(lastRequest()).toEqual({
      keyword: 'mouse',
      minPrice: 10,
      maxPrice: 200,
      minRating: 4,
      page: 0,
      size: 20,
    })
  })

  it('removes reset price bounds and rating from API requests while retaining the brand', async () => {
    const { wrapper } = await renderSearch()
    const sidebar = wrapper.findComponent(FilterSidebar)
    sidebar.findComponent(Checkbox).vm.$emit('update:modelValue', ['Logitech'])
    sidebar.findComponent(Slider).vm.$emit('update:modelValue', [10, 200])
    sidebar.findComponent(Rating).vm.$emit('update:modelValue', 4)
    await flushPromises()

    sidebar.findComponent(Slider).vm.$emit('update:modelValue', [0, 1000])
    await flushPromises()
    expect(lastRequest()).toEqual({
      keyword: 'mouse',
      brand: 'Logitech',
      minRating: 4,
      page: 0,
      size: 20,
    })

    sidebar.findComponent(Rating).vm.$emit('update:modelValue', 0)
    await flushPromises()
    expect(lastRequest()).toEqual({ keyword: 'mouse', brand: 'Logitech', page: 0, size: 20 })
  })

  it('uses the current route keyword even when a filter snapshot contains an older keyword', async () => {
    const { wrapper } = await renderSearch({ keyword: 'older', brand: 'Logitech' })
    expect(lastRequest()?.keyword).toBe('mouse')
    wrapper.findComponent(FilterSidebar).vm.$emit('filter', { keyword: 'older', minRating: 4 })
    await flushPromises()
    expect(lastRequest()).toEqual({ keyword: 'mouse', minRating: 4, page: 0, size: 20 })
  })

  it('initializes sidebar controls from existing filters so tag removal preserves the other selections', async () => {
    const { wrapper } = await renderSearch({
      keyword: 'older',
      brand: 'Logitech',
      minPrice: 10,
      maxPrice: 200,
      minRating: 4,
    })
    const sidebar = wrapper.findComponent(FilterSidebar)
    expect(sidebar.findComponent(Checkbox).props('modelValue')).toEqual(['Logitech'])
    expect(sidebar.findComponent(Slider).props('modelValue')).toEqual([10, 200])
    expect(sidebar.findComponent(Rating).props('modelValue')).toBe(4)
    await removalButton(wrapper, '4+ stars').trigger('click')
    await flushPromises()
    expect(lastRequest()).toEqual({
      keyword: 'mouse',
      brand: 'Logitech',
      minPrice: 10,
      maxPrice: 200,
      page: 0,
      size: 20,
    })
  })

  it('resets sidebar controls on a new keyword so later input cannot revive previous filters', async () => {
    const { wrapper, router } = await renderSearch()
    const sidebar = wrapper.findComponent(FilterSidebar)
    sidebar.findComponent(Checkbox).vm.$emit('update:modelValue', ['Logitech'])
    sidebar.findComponent(Slider).vm.$emit('update:modelValue', [10, 200])
    sidebar.findComponent(Rating).vm.$emit('update:modelValue', 4)
    await flushPromises()

    await router.push('/search?keyword=keyboard')
    await flushPromises()
    expect(lastRequest()).toEqual({ keyword: 'keyboard', page: 0, size: 20 })
    expect(sidebar.findComponent(Checkbox).props('modelValue')).toEqual([])
    expect(sidebar.findComponent(Slider).props('modelValue')).toEqual([0, 1000])
    expect(sidebar.findComponent(Rating).props('modelValue')).toBe(0)

    sidebar.findComponent(Rating).vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(lastRequest()).toEqual({ keyword: 'keyboard', minRating: 3, page: 0, size: 20 })
  })

  it.each([
    {
      key: 'brand',
      label: 'Brand: Logitech',
      remaining: { minPrice: 10, maxPrice: 200, minRating: 3 },
    },
    {
      key: 'minPrice',
      label: 'Min: $10',
      remaining: { brand: 'Logitech', maxPrice: 200, minRating: 3 },
    },
    {
      key: 'maxPrice',
      label: 'Max: $200',
      remaining: { brand: 'Logitech', minPrice: 10, minRating: 3 },
    },
    {
      key: 'minRating',
      label: '4+ stars',
      remaining: { brand: 'Logitech', minPrice: 20, maxPrice: 300 },
    },
  ])(
    'does not revive $key after its tag is removed and another control changes',
    async ({ key, label, remaining }) => {
      const { wrapper } = await renderSearch()
      const sidebar = wrapper.findComponent(FilterSidebar)
      sidebar.findComponent(Checkbox).vm.$emit('update:modelValue', ['Logitech'])
      sidebar.findComponent(Slider).vm.$emit('update:modelValue', [10, 200])
      sidebar.findComponent(Rating).vm.$emit('update:modelValue', 4)
      await flushPromises()

      await removalButton(wrapper, label).trigger('click')
      await flushPromises()
      expect(lastRequest()).not.toHaveProperty(key)

      if (key === 'minRating')
        sidebar.findComponent(Slider).vm.$emit('update:modelValue', [20, 300])
      else sidebar.findComponent(Rating).vm.$emit('update:modelValue', 3)
      await flushPromises()
      expect(lastRequest()).toEqual({ ...remaining, keyword: 'mouse', page: 0, size: 20 })
    },
  )

  describe('Closed mobile filter state', () => {
    it('removes every tag while closed and cannot revive cleared values on reopen or another edit', async () => {
      const { wrapper } = await renderSearch({
        brand: 'Logitech',
        minPrice: 10,
        maxPrice: 200,
        minRating: 4,
      })
      const sidebar = wrapper.findComponent(FilterSidebar)
      await sidebar.get('button[aria-expanded]').trigger('click')
      expect(sidebar.get('[aria-expanded]').attributes('aria-expanded')).toBe('false')
      for (const label of ['Brand: Logitech', 'Min: $10', 'Max: $200', '4+ stars']) {
        await removalButton(wrapper, label).trigger('click')
        await flushPromises()
      }
      expect(lastRequest()).toEqual({ keyword: 'mouse', page: 0, size: 20 })
      await sidebar.get('button[aria-expanded]').trigger('click')
      await sidebar.get('input[type="checkbox"]').setValue(true)
      await flushPromises()
      expect(lastRequest()).toEqual({ keyword: 'mouse', brand: 'Logitech', page: 0, size: 20 })
      expect(wrapper.findAll('.filter-sidebar')).toHaveLength(1)
    })
  })
  it('keeps the focused brand checkbox mounted while a filtered request is pending and completes', async () => {
    const { wrapper } = await renderSearch(undefined, document.body)
    const response = await vi.mocked(searchProducts).mock.results[0]!.value
    let resolve!: (value: typeof response) => void
    vi.mocked(searchProducts).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const checkbox = wrapper.get<HTMLInputElement>('.filter-sidebar input[type="checkbox"]')
    checkbox.element.focus()
    await checkbox.setValue(true)
    await flushPromises()
    expect(document.activeElement).toBe(checkbox.element)
    expect(checkbox.element.isConnected).toBe(true)
    resolve(response)
    await flushPromises()
    expect(document.activeElement).toBe(checkbox.element)
    expect(lastRequest()).toMatchObject({ keyword: 'mouse', brand: 'Logitech' })
  })
})
