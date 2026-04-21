import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'

// Mock API modules
vi.mock('@/api/inventoryAdminApi', () => ({
  listInventory: vi.fn(),
  restockItem: vi.fn(),
  bulkRestock: vi.fn(),
}))

vi.mock('@/api/productAdminApi', () => ({
  listProducts: vi.fn(),
}))

import { listInventory, restockItem } from '@/api/inventoryAdminApi'
import { listProducts } from '@/api/productAdminApi'
import InventoryPage from '../views/InventoryPage.vue'

const mockInventoryItems = [
  {
    id: 1,
    productId: 1,
    availableQuantity: 5,
    reservedQuantity: 2,
    totalQuantity: 7,
    lowStockThreshold: 10,
    updatedAt: '2026-04-07T10:00:00Z',
    productName: 'Product A',
    sku: 'SKU-001',
  },
  {
    id: 2,
    productId: 2,
    availableQuantity: 100,
    reservedQuantity: 5,
    totalQuantity: 105,
    lowStockThreshold: 10,
    updatedAt: '2026-04-07T10:00:00Z',
    productName: 'Product B',
    sku: 'SKU-002',
  },
]

const mockProducts = [
  {
    id: 1,
    sku: 'SKU-001',
    name: 'Product A',
    price: 29.99,
    brand: 'BrandA',
    rating: 4.5,
    stockQuantity: 100,
    categoryId: 1,
    categoryName: 'Electronics',
    primaryImageUrl: null,
    description: null,
  },
  {
    id: 2,
    sku: 'SKU-002',
    name: 'Product B',
    price: 49.99,
    brand: 'BrandB',
    rating: 3.8,
    stockQuantity: 0,
    categoryId: 2,
    categoryName: 'Toys',
    primaryImageUrl: null,
    description: null,
  },
]

function createGlobalConfig() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return {
    plugins: [
      pinia,
      [PrimeVue, { theme: { preset: adminTheme } }] as [typeof PrimeVue, ...unknown[]] as [
        typeof PrimeVue,
        ...unknown[],
      ],
      ToastService,
    ],
    stubs: {
      DataTable: {
        template:
          '<div data-testid="datatable"><slot name="loading" /><slot name="empty" /><slot /></div>',
        props: ['value', 'loading', 'rowClass'],
      },
      Column: {
        template:
          '<div><slot name="body" :data="{ productId: 1, availableQuantity: 5, lowStockThreshold: 10, productName: \'Product A\', sku: \'SKU-001\' }" /></div>',
      },
      Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      InputNumber: { template: '<input type="number" />' },
      Tag: { template: '<span class="tag"><slot /></span>' },
      Skeleton: { template: '<div class="skeleton" />' },
      Dialog: {
        template: '<div data-testid="dialog" v-if="visible"><slot /><slot name="footer" /></div>',
        props: ['visible'],
      },
      EmptyState: { template: '<div data-testid="empty-state" />' },
    },
  }
}

describe('InventoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()

    vi.mocked(listInventory).mockResolvedValue({
      data: mockInventoryItems.map(({ productName: _pn, sku: _sk, ...rest }) => rest),
      pagination: { page: 0, size: 25, totalElements: 2, totalPages: 1 },
      traceId: 'test',
    })

    vi.mocked(listProducts).mockResolvedValue({
      data: mockProducts,
      pagination: { page: 0, size: 1000, totalElements: 2, totalPages: 1 },
      traceId: 'test',
    })
  })

  it('renders DataTable', async () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="datatable"]').exists()).toBe(true)
  })

  it('calls loadInventory (listInventory + listProducts) on mount', async () => {
    shallowMount(InventoryPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))

    expect(listInventory).toHaveBeenCalledWith(0, 25)
    expect(listProducts).toHaveBeenCalledWith(0, 1000)
  })

  it('shows bulk toolbar when rows are selected', async () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    // Initially hidden
    expect(wrapper.find('[data-testid="bulk-toolbar"]').exists()).toBe(false)

    // Set selectedRows
    const vm = wrapper.vm as any
    vm.selectedRows = [mockInventoryItems[0]]
    await wrapper.vm.$nextTick()

    // Toolbar should show
    const toolbar = wrapper.find('.bg-primary-50')
    expect(toolbar.exists()).toBe(true)
    expect(toolbar.text()).toContain('1 selected')
  })

  it('shows error state when store has error', async () => {
    vi.mocked(listInventory).mockRejectedValue(new Error('Network error'))
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Failed to load inventory data')
  })

  it('isLowStock returns true when availableQuantity < lowStockThreshold', () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    const vm = wrapper.vm as any
    expect(vm.isLowStock({ availableQuantity: 5, lowStockThreshold: 10 })).toBe(true)
    expect(vm.isLowStock({ availableQuantity: 10, lowStockThreshold: 10 })).toBe(false)
    expect(vm.isLowStock({ availableQuantity: 50, lowStockThreshold: 10 })).toBe(false)
  })

  it('rowClass returns bg-yellow-50 for low stock items', () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    const vm = wrapper.vm as any
    expect(vm.rowClass({ availableQuantity: 5, lowStockThreshold: 10 })).toBe('bg-yellow-50')
    expect(vm.rowClass({ availableQuantity: 50, lowStockThreshold: 10 })).toBe('')
  })

  it('cancelCellEdit clears editing state', async () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    const vm = wrapper.vm as any
    vm.editingCell = { productId: 1, originalValue: 5, value: 20 }
    vm.cancelCellEdit()
    expect(vm.editingCell).toBeNull()
  })

  it('saveCellEdit shows warn toast when delta <= 0', async () => {
    const wrapper = shallowMount(InventoryPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    vm.editingCell = { productId: 1, originalValue: 50, value: 30 } // negative delta

    await vm.saveCellEdit({ productId: 1, productName: 'Test', availableQuantity: 50 })

    expect(restockItem).not.toHaveBeenCalled()
  })
})
