import { describe, it, expect } from 'vitest'
import { colors, typography, spacing, shadows } from '@robo-mart/shared'

describe('Design Tokens', () => {
  it('should export primary color palette with all required shades', () => {
    expect(colors.primary[50]).toBe('#EFF6FF')
    expect(colors.primary[500]).toBe('#3B82F6')
    expect(colors.primary[600]).toBe('#2563EB')
    expect(colors.primary[900]).toBe('#1E3A5F')
  })

  it('should export semantic colors with 50 and 500 variants', () => {
    expect(colors.success[50]).toBe('#F0FDF4')
    expect(colors.success[500]).toBe('#22C55E')
    expect(colors.warning[50]).toBe('#FFFBEB')
    expect(colors.warning[500]).toBe('#F59E0B')
    expect(colors.error[50]).toBe('#FEF2F2')
    expect(colors.error[500]).toBe('#EF4444')
    expect(colors.info[50]).toBe('#EFF6FF')
    expect(colors.info[500]).toBe('#3B82F6')
  })

  it('should export neutral gray palette', () => {
    expect(colors.gray[50]).toBe('#F9FAFB')
    expect(colors.gray[200]).toBe('#E5E7EB')
    expect(colors.gray[600]).toBe('#4B5563')
    expect(colors.gray[900]).toBe('#111827')
  })

  it('should export typography with Inter font and customer scale', () => {
    expect(typography.fontFamily.sans).toContain('Inter')
    expect(typography.customer.body.size).toBe('16px')
    expect(typography.customer.h1.size).toBe('30px')
    expect(typography.customer.h1.weight).toBe('700')
  })

  it('should export spacing scale based on 4px unit', () => {
    expect(spacing.xs).toBe('4px')
    expect(spacing.sm).toBe('8px')
    expect(spacing.md).toBe('16px')
    expect(spacing.lg).toBe('24px')
    expect(spacing.xl).toBe('32px')
    expect(spacing['2xl']).toBe('48px')
    expect(spacing['3xl']).toBe('64px')
  })

  it('should export shadow tokens', () => {
    expect(shadows.sm).toContain('rgba')
    expect(shadows.md).toContain('rgba')
    expect(shadows.lg).toContain('rgba')
    expect(shadows.none).toBe('none')
  })
})
