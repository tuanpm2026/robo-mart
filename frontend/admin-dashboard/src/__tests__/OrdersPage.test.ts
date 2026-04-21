import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'

vi.mock('@/api/orderAdminApi', () => ({
  listOrders: vi.fn(),
  getOrderDetail: vi.fn(),
  updateOrderStatus: vi.fn(),
}))

import { listOrders, getOrderDetail, updateOrderStatus } from '@/api/orderAdminApi'
import OrdersPage from '../views/OrdersPage.vue'

const mockOrders = [
  {
    id: 1,
    userId: 'user-1',
    createdAt: '2026-04-07T10:00:00Z',
    totalAmount: 149.99,
    status: 'CONFIRMED',
    itemCount: 3,
    cancellationReason: null,
  },
  {
    id: 2,
    userId: 'user-2',
    createdAt: '2026-04-06T10:00:00Z',
    totalAmount: 29.99,
    status: 'DELIVERED',
    itemCount: 1,
    cancellationReason: null,
  },
  {
    id: 3,
    userId: 'user-3',
    createdAt: '2026-04-05T10:00:00Z',
    totalAmount: 99.99,
    status: 'CANCELLED',
    itemCount: 2,
    cancellationReason: 'Changed my mind',
  },
]

const mockOrderDetail = {
  id: 1,
  userId: 'user-1',
  createdAt: '2026-04-07T10:00:00Z',
  updatedAt: '2026-04-07T12:00:00Z',
  totalAmount: 149.99,
  status: 'CONFIRMED',
  shippingAddress: '123 Test St, City, ST 12345',
  cancellationReason: null,
  items: [
    {
      productId: 10,
      productName: 'Widget A',
      quantity: 2,
      unitPrice: 49.99,
      subtotal: 99.98,
    },
    {
      productId: 20,
      productName: 'Widget B',
      quantity: 1,
      unitPrice: 50.01,
      subtotal: 50.01,
    },
  ],
  statusHistory: [
    { status: 'PENDING', changedAt: '2026-04-07T10:00:00Z' },
    { status: 'CONFIRMED', changedAt: '2026-04-07T10:05:00Z' },
  ],
}

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
        props: ['value', 'loading', 'paginator', 'rows', 'totalRecords', 'lazy'],
      },
      Column: {
        template:
          "<div><slot name=\"body\" :data=\"{ id: 1, userId: 'user-1', createdAt: '2026-04-07T10:00:00Z', totalAmount: 149.99, status: 'CONFIRMED', itemCount: 3, cancellationReason: null }\" /></div>",
      },
      Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      Tag: {
        template: '<span class="tag" :data-severity="$attrs.severity">{{ $attrs.value }}</span>',
      },
      Select: {
        template: '<select data-testid="status-select"><slot /></select>',
        props: ['modelValue', 'options'],
      },
      MultiSelect: {
        template: '<div data-testid="status-filter"><slot /></div>',
        props: ['modelValue', 'options'],
      },
      Skeleton: { template: '<div class="skeleton" />' },
      Timeline: {
        template:
          '<div data-testid="timeline"><slot name="content" :item="{ status: \'PENDING\', changedAt: \'2026-04-07T10:00:00Z\' }" /></div>',
        props: ['value'],
      },
      SlideOverPanel: {
        template: '<div data-testid="slide-over" v-if="visible"><slot /></div>',
        props: ['visible', 'title'],
      },
      EmptyState: { template: '<div data-testid="empty-state" />' },
    },
  }
}

describe('OrdersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()

    vi.mocked(listOrders).mockResolvedValue({
      data: mockOrders,
      pagination: { page: 0, size: 25, totalElements: 3, totalPages: 1 },
      traceId: 'test-trace',
    })

    vi.mocked(getOrderDetail).mockResolvedValue(mockOrderDetail)

    vi.mocked(updateOrderStatus).mockResolvedValue({
      id: 1,
      userId: 'user-1',
      createdAt: '2026-04-07T10:00:00Z',
      totalAmount: 149.99,
      status: 'SHIPPED',
      itemCount: 3,
      cancellationReason: null,
    })
  })

  it('renders DataTable', async () => {
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="datatable"]').exists()).toBe(true)
  })

  it('calls listOrders on mount', async () => {
    shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))

    expect(listOrders).toHaveBeenCalledWith(0, 25, undefined)
  })

  it('renders status filter MultiSelect', async () => {
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="status-filter"]').exists()).toBe(true)
  })

  it('shows error state when store has error', async () => {
    vi.mocked(listOrders).mockRejectedValue(new Error('Network error'))
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Failed to load orders')
  })

  it('opens detail slide-over when view button clicked', async () => {
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    await vm.openDetail(mockOrders[0])
    await wrapper.vm.$nextTick()

    expect(getOrderDetail).toHaveBeenCalledWith(1)
  })

  it('calls updateOrderStatus with correct params', async () => {
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    await new Promise((r) => setTimeout(r, 10))
    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any
    await vm.onStatusChange(mockOrders[0], 'SHIPPED')

    expect(updateOrderStatus).toHaveBeenCalledWith(1, 'SHIPPED')
  })

  it('formats currency correctly', () => {
    const wrapper = shallowMount(OrdersPage, { global: createGlobalConfig() })
    const vm = wrapper.vm as any
    expect(vm.formatCurrency(149.99)).toBe('$149.99')
    expect(vm.formatCurrency(0)).toBe('$0.00')
  })
})
