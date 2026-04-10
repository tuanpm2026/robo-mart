import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import AlertCard from '@/components/dashboard/AlertCard.vue'

const mockToastAdd = vi.fn()
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: mockToastAdd }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/useInventoryStore', () => ({
  useInventoryStore: () => ({ restockItem: vi.fn().mockResolvedValue(undefined) }),
}))

describe('AlertCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountCard(props: Record<string, unknown>) {
    return mount(AlertCard, {
      props,
      global: { plugins: [PrimeVue] },
    })
  }

  const defaultProps = {
    type: 'low-stock' as const,
    productId: 1,
    productName: 'Widget Pro',
    currentStock: 2,
    threshold: 10,
  }

  it('renders danger severity tag when currentStock is 0', () => {
    const wrapper = mountCard({ ...defaultProps, currentStock: 0 })
    const tag = wrapper.findComponent({ name: 'Tag' })
    expect(tag.exists()).toBe(true)
    expect(tag.props('severity')).toBe('danger')
  })

  it('renders warn severity tag when currentStock is above 0', () => {
    const wrapper = mountCard({ ...defaultProps, currentStock: 3 })
    const tag = wrapper.findComponent({ name: 'Tag' })
    expect(tag.props('severity')).toBe('warn')
  })

  it('toggles inline restock form when Quick Restock is clicked', async () => {
    const wrapper = mountCard(defaultProps)

    expect(wrapper.find('.restock-form').exists()).toBe(false)

    const buttons = wrapper.findAllComponents({ name: 'Button' })
    const restockBtn = buttons.find((b) => b.props('label') === 'Quick Restock')
    await restockBtn!.trigger('click')

    expect(wrapper.find('.restock-form').exists()).toBe(true)
  })

  it('emits dismissed and shows success toast after successful restock', async () => {
    const wrapper = mountCard(defaultProps)

    const restockBtn = wrapper.findAllComponents({ name: 'Button' }).find((b) => b.props('label') === 'Quick Restock')
    await restockBtn!.trigger('click')

    const updateBtn = wrapper.findAllComponents({ name: 'Button' }).find((b) => b.props('label') === 'Update')
    await updateBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    expect(mockToastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'success', summary: 'Stock updated' }))
    expect(wrapper.emitted('dismissed')).toBeTruthy()
  })
})
