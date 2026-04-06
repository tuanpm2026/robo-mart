import { describe, it, expect } from 'vitest'
import { adminTheme } from '@robo-mart/shared'

describe('adminTheme', () => {
  it('exports adminTheme as a defined value', () => {
    expect(adminTheme).toBeDefined()
  })

  it('is a PrimeVue preset object with semantic key', () => {
    expect(adminTheme).toHaveProperty('semantic')
  })

  it('has admin primary color set to primary-700 (#1D4ED8)', () => {
    const primary = (adminTheme as any).semantic?.colorScheme?.light?.primary
    expect(primary?.color).toBe('#1D4ED8')
  })

  it('has admin border-radius sm and md both set to 4px', () => {
    const borderRadius = (adminTheme as any).semantic?.borderRadius
    expect(borderRadius?.sm).toBe('4px')
    expect(borderRadius?.md).toBe('4px')
  })

  it('has admin transition duration set to 150ms', () => {
    const transitionDuration = (adminTheme as any).semantic?.transitionDuration
    expect(transitionDuration).toBe('150ms')
  })
})
