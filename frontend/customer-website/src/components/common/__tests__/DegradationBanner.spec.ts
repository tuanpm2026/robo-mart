import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import DegradationBanner from '../DegradationBanner.vue'
import { useUiStore } from '@/stores/useUiStore'

describe('DegradationBanner', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
  })

  it('renders nothing when tier is normal', () => {
    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-banner').exists()).toBe(false)
    expect(wrapper.find('.degradation-overlay').exists()).toBe(false)
  })

  it('renders yellow banner when tier is partial and banner not dismissed', () => {
    const store = useUiStore()
    store.setDegradationTier('partial')

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-banner--partial').exists()).toBe(true)
    expect(wrapper.find('.degradation-banner__message').text()).toContain('temporarily limited')
  })

  it('banner not shown when tier is partial but isBannerDismissed is true', () => {
    const store = useUiStore()
    store.setDegradationTier('partial')
    store.dismissBanner()

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-banner').exists()).toBe(false)
  })

  it('dismiss button calls uiStore.dismissBanner', async () => {
    const store = useUiStore()
    store.setDegradationTier('partial')

    const wrapper = mount(DegradationBanner)
    await wrapper.find('.degradation-banner__dismiss').trigger('click')
    expect(store.isBannerDismissed).toBe(true)
  })

  it('renders full-page overlay when tier is maintenance', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-overlay').exists()).toBe(true)
    expect(wrapper.find('.degradation-overlay__title').text()).toBe("We'll be right back")
    expect(wrapper.find('.degradation-overlay__message').text()).toContain('performing maintenance')
  })

  it('maintenance overlay has role alertdialog', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-overlay').attributes('role')).toBe('alertdialog')
  })

  it('maintenance overlay has no dismiss button', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-banner__dismiss').exists()).toBe(false)
  })

  it('partial banner has role alert', () => {
    const store = useUiStore()
    store.setDegradationTier('partial')

    const wrapper = mount(DegradationBanner)
    expect(wrapper.find('.degradation-banner').attributes('role')).toBe('alert')
  })
})
