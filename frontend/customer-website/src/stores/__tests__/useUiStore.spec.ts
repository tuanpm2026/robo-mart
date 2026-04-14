import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUiStore } from '../useUiStore'

describe('useUiStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('initial tier is normal', () => {
    const store = useUiStore()
    expect(store.degradationTier).toBe('normal')
  })

  it('initial isBannerDismissed is false when sessionStorage is empty', () => {
    const store = useUiStore()
    expect(store.isBannerDismissed).toBe(false)
  })

  it('initial isBannerDismissed is true when sessionStorage has dismissed flag', () => {
    sessionStorage.setItem('degradation-banner-dismissed', 'true')
    const store = useUiStore()
    expect(store.isBannerDismissed).toBe(true)
  })

  it('setDegradationTier sets tier to partial', () => {
    const store = useUiStore()
    store.setDegradationTier('partial')
    expect(store.degradationTier).toBe('partial')
  })

  it('setDegradationTier sets tier to maintenance', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')
    expect(store.degradationTier).toBe('maintenance')
  })

  it('once tier is maintenance, setDegradationTier partial does NOT downgrade', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')
    store.setDegradationTier('partial')
    expect(store.degradationTier).toBe('maintenance')
  })

  it('once tier is maintenance, setDegradationTier normal does NOT downgrade', () => {
    const store = useUiStore()
    store.setDegradationTier('maintenance')
    store.setDegradationTier('normal')
    expect(store.degradationTier).toBe('maintenance')
  })

  it('dismissBanner sets isBannerDismissed to true', () => {
    const store = useUiStore()
    store.dismissBanner()
    expect(store.isBannerDismissed).toBe(true)
  })

  it('dismissBanner writes to sessionStorage', () => {
    const store = useUiStore()
    store.dismissBanner()
    expect(sessionStorage.getItem('degradation-banner-dismissed')).toBe('true')
  })

  it('resetDegradation resets tier to normal', () => {
    const store = useUiStore()
    store.setDegradationTier('partial')
    store.resetDegradation()
    expect(store.degradationTier).toBe('normal')
  })

  it('resetDegradation resets isBannerDismissed to false', () => {
    const store = useUiStore()
    store.dismissBanner()
    store.resetDegradation()
    expect(store.isBannerDismissed).toBe(false)
  })

  it('resetDegradation clears sessionStorage', () => {
    const store = useUiStore()
    store.dismissBanner()
    store.resetDegradation()
    expect(sessionStorage.getItem('degradation-banner-dismissed')).toBeNull()
  })
})
