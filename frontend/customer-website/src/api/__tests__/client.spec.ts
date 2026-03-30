import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import type { AxiosInstance } from 'axios'

// We need to test the client module in isolation, so we re-import it fresh
describe('apiClient', () => {
  let apiClient: AxiosInstance
  let setAuthAccessor: (accessor: () => {
    accessToken: string | null
    userId: string | null
    refreshToken: () => Promise<boolean>
    logout: () => Promise<void>
  }) => void
  let getAnonymousUserId: () => string

  beforeEach(async () => {
    vi.resetModules()
    // Mock localStorage
    const store: Record<string, string> = {}
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key) => store[key] || null)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key, value) => { store[key] = value })

    const clientModule = await import('../client')
    apiClient = clientModule.default
    setAuthAccessor = clientModule.setAuthAccessor
    getAnonymousUserId = clientModule.getAnonymousUserId
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should have correct baseURL', () => {
    expect(apiClient.defaults.baseURL).toBe('http://localhost:8080')
  })

  it('should generate anonymous user ID', () => {
    const id = getAnonymousUserId()
    expect(id).toBeTruthy()
    expect(typeof id).toBe('string')
  })

  it('should attach X-User-Id header for anonymous users', async () => {
    const adapter = vi.fn().mockResolvedValue({ data: {}, status: 200, headers: {}, config: {}, statusText: 'OK' })
    apiClient.defaults.adapter = adapter

    await apiClient.get('/test')

    const config = adapter.mock.calls[0][0]
    expect(config.headers['X-User-Id']).toBeTruthy()
    expect(config.headers['Authorization']).toBeUndefined()
  })

  it('should attach Bearer token and authenticated user ID when authenticated', async () => {
    setAuthAccessor(() => ({
      accessToken: 'jwt-token-123',
      userId: 'user-uuid-456',
      refreshToken: vi.fn().mockResolvedValue(true),
      logout: vi.fn(),
    }))

    const adapter = vi.fn().mockResolvedValue({ data: {}, status: 200, headers: {}, config: {}, statusText: 'OK' })
    apiClient.defaults.adapter = adapter

    await apiClient.get('/test')

    const config = adapter.mock.calls[0][0]
    expect(config.headers['Authorization']).toBe('Bearer jwt-token-123')
    expect(config.headers['X-User-Id']).toBe('user-uuid-456')
  })

  it('should fallback to anonymous ID when userId is null but authenticated', async () => {
    setAuthAccessor(() => ({
      accessToken: 'jwt-token',
      userId: null,
      refreshToken: vi.fn().mockResolvedValue(true),
      logout: vi.fn(),
    }))

    const adapter = vi.fn().mockResolvedValue({ data: {}, status: 200, headers: {}, config: {}, statusText: 'OK' })
    apiClient.defaults.adapter = adapter

    await apiClient.get('/test')

    const config = adapter.mock.calls[0][0]
    expect(config.headers['Authorization']).toBe('Bearer jwt-token')
    expect(config.headers['X-User-Id']).toBeTruthy()
  })

  it('should retry with refreshed token on 401 response', async () => {
    const mockRefresh = vi.fn().mockResolvedValue(true)
    const mockLogout = vi.fn()
    let callCount = 0

    setAuthAccessor(() => ({
      accessToken: callCount === 0 ? 'expired-token' : 'fresh-token',
      userId: 'user-1',
      refreshToken: mockRefresh,
      logout: mockLogout,
    }))

    const adapter = vi.fn().mockImplementation((config) => {
      callCount++
      if (callCount === 1) {
        return Promise.reject({
          config,
          response: { status: 401, data: {}, headers: {}, statusText: 'Unauthorized' },
          isAxiosError: true,
        })
      }
      return Promise.resolve({ data: { ok: true }, status: 200, headers: {}, config, statusText: 'OK' })
    })
    apiClient.defaults.adapter = adapter

    const result = await apiClient.get('/protected')

    expect(mockRefresh).toHaveBeenCalledTimes(1)
    expect(result.data).toEqual({ ok: true })
    expect(callCount).toBe(2)
  })

  it('should logout when token refresh fails on 401', async () => {
    const mockRefresh = vi.fn().mockResolvedValue(false)
    const mockLogout = vi.fn().mockResolvedValue(undefined)

    setAuthAccessor(() => ({
      accessToken: 'expired-token',
      userId: 'user-1',
      refreshToken: mockRefresh,
      logout: mockLogout,
    }))

    const adapter = vi.fn().mockRejectedValue({
      config: { headers: {}, _retried: undefined },
      response: { status: 401, data: {}, headers: {}, statusText: 'Unauthorized' },
      isAxiosError: true,
    })
    apiClient.defaults.adapter = adapter

    await expect(apiClient.get('/protected')).rejects.toThrow('Session expired')
    expect(mockRefresh).toHaveBeenCalled()
    expect(mockLogout).toHaveBeenCalled()
  })
})
