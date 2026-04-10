import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useWebSocketStore } from '@/stores/useWebSocketStore'
import ConnectionStatus from '@/components/common/ConnectionStatus.vue'

vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: vi.fn() }),
}))

describe('ConnectionStatus', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows green dot when connected', async () => {
    const store = useWebSocketStore()
    store.setConnectionStatus('connected')
    const wrapper = mount(ConnectionStatus)
    expect(wrapper.find('.ws-status-dot--connected').exists()).toBe(true)
    expect(wrapper.find('.ws-status-label').exists()).toBe(false)
  })

  it('shows yellow dot and Reconnecting label when reconnecting', async () => {
    const store = useWebSocketStore()
    store.setConnectionStatus('reconnecting')
    const wrapper = mount(ConnectionStatus)
    expect(wrapper.find('.ws-status-dot--reconnecting').exists()).toBe(true)
    expect(wrapper.text()).toContain('Reconnecting...')
  })

  it('shows red dot and Disconnected label when disconnected', async () => {
    const store = useWebSocketStore()
    store.setConnectionStatus('disconnected')
    const wrapper = mount(ConnectionStatus)
    expect(wrapper.find('.ws-status-dot--disconnected').exists()).toBe(true)
    expect(wrapper.text()).toContain('Disconnected')
  })
})
