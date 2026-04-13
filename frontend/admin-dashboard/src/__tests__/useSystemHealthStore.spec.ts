import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useSystemHealthStore } from '@/stores/useSystemHealthStore'
import * as systemHealthApi from '@/api/systemHealthApi'
import type { ServiceHealthData, SystemHealthResponse } from '@/api/systemHealthApi'

vi.mock('@/api/systemHealthApi')

function makeService(overrides: Partial<ServiceHealthData> = {}): ServiceHealthData {
  return {
    service: 'product-service',
    displayName: 'Product Service',
    actuatorStatus: 'UP',
    p95ResponseTimeMs: 45,
    cpuPercent: 12.5,
    memoryPercent: 60.0,
    dbPoolActive: 2,
    dbPoolMax: 10,
    kafkaConsumerLag: 0,
    consumerGroup: 'product-service-product-index-group',
    checkedAt: new Date().toISOString(),
    ...overrides,
  }
}

function makeHealthResponse(services: ServiceHealthData[]): SystemHealthResponse {
  return {
    services,
    checkedAt: new Date().toISOString(),
  }
}

describe('useSystemHealthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loadHealth sets services from API response', async () => {
    const mockServices = [makeService(), makeService({ service: 'cart-service', displayName: 'Cart Service' })]
    vi.mocked(systemHealthApi.fetchSystemHealth).mockResolvedValue(makeHealthResponse(mockServices))

    const store = useSystemHealthStore()
    await store.loadHealth()

    expect(store.services).toHaveLength(2)
    expect(store.services[0].service).toBe('product-service')
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('overallHealth returns healthy when all UP and p95 < 200', () => {
    vi.mocked(systemHealthApi.fetchSystemHealth).mockResolvedValue(makeHealthResponse([]))

    const store = useSystemHealthStore()
    store.services = [
      makeService({ actuatorStatus: 'UP', p95ResponseTimeMs: 45 }),
      makeService({ service: 'cart-service', actuatorStatus: 'UP', p95ResponseTimeMs: 80 }),
    ]

    expect(store.overallHealth).toBe('healthy')
  })

  it('overallHealth returns down when any service is DOWN', () => {
    const store = useSystemHealthStore()
    store.services = [
      makeService({ actuatorStatus: 'UP', p95ResponseTimeMs: 45 }),
      makeService({ service: 'order-service', actuatorStatus: 'DOWN', p95ResponseTimeMs: null }),
    ]

    expect(store.overallHealth).toBe('down')
  })

  it('updateFromWebSocket updates services and lagHistory rolling buffer', () => {
    const store = useSystemHealthStore()
    const svc = makeService({ kafkaConsumerLag: 5 })

    // Simulate 32 WebSocket updates — buffer should cap at 30
    for (let i = 0; i < 32; i++) {
      store.updateFromWebSocket(makeHealthResponse([{ ...svc, kafkaConsumerLag: i }]))
    }

    expect(store.services[0].kafkaConsumerLag).toBe(31)
    expect(store.lagHistory['product-service']).toHaveLength(30)
  })
})
