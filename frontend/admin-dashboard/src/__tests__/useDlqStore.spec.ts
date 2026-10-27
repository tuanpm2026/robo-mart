import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useDlqStore } from '@/stores/useDlqStore'
import * as dlqApi from '@/api/dlqApi'

vi.mock('@/api/dlqApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/dlqApi')>()
  return { ...actual, fetchDlqEvents: vi.fn(), retryDlqEvent: vi.fn(), retryAllDlqEvents: vi.fn() }
})

const mockEvent: dlqApi.DlqEvent = {
  id: 1,
  eventType: 'order.created',
  aggregateId: 'order-123',
  originalTopic: 'order.created',
  errorClass: 'java.lang.RuntimeException',
  errorMessage: 'Connection refused',
  payloadPreview: '{"orderId":"order-123"}',
  retryCount: 2,
  status: 'PENDING',
  firstFailedAt: '2026-04-10T10:00:00Z',
  lastAttemptedAt: '2026-04-10T10:05:00Z',
  replayable: true,
  replayClaimUntil: null,
}

const mockPagedResponse = {
  data: [mockEvent],
  pagination: { page: 0, size: 25, totalElements: 1, totalPages: 1 },
  traceId: 'trace-123',
}

describe('useDlqStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads events and sets pagination state', async () => {
    vi.mocked(dlqApi.fetchDlqEvents).mockResolvedValue(mockPagedResponse)

    const store = useDlqStore()
    await store.loadEvents()

    expect(store.events).toHaveLength(1)
    expect(store.events[0]!.eventType).toBe('order.created')
    expect(store.totalElements).toBe(1)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('retryEvent triggers reload of events', async () => {
    vi.mocked(dlqApi.retryDlqEvent).mockResolvedValue({
      message: 'Replay acknowledged',
      acknowledged: true,
    })
    vi.mocked(dlqApi.fetchDlqEvents).mockResolvedValue({ ...mockPagedResponse, data: [] })

    const store = useDlqStore()
    await store.retryEvent(1)

    expect(dlqApi.retryDlqEvent).toHaveBeenCalledWith(1)
    expect(dlqApi.fetchDlqEvents).toHaveBeenCalled()
    expect(store.events).toHaveLength(0)
  })

  it('retryAll triggers reload of events', async () => {
    vi.mocked(dlqApi.retryAllDlqEvents).mockResolvedValue('3 events processed')
    vi.mocked(dlqApi.fetchDlqEvents).mockResolvedValue({ ...mockPagedResponse, data: [] })

    const store = useDlqStore()
    await store.retryAll()

    expect(dlqApi.retryAllDlqEvents).toHaveBeenCalledOnce()
    expect(dlqApi.fetchDlqEvents).toHaveBeenCalled()
    expect(store.events).toHaveLength(0)
  })
})

describe('DLQ replay eligibility', () => {
  it('requires original bytes and waits until an existing replay claim expires', () => {
    const now = Date.parse('2026-10-26T12:00:00Z')
    expect(dlqApi.canReplayDlqEvent(mockEvent, now)).toBe(true)
    expect(dlqApi.canReplayDlqEvent({ ...mockEvent, replayable: false }, now)).toBe(false)
    expect(dlqApi.canReplayDlqEvent({ ...mockEvent, status: 'LEGACY_REVIEW' }, now)).toBe(false)
    expect(
      dlqApi.canReplayDlqEvent(
        { ...mockEvent, status: 'REPLAYING', replayClaimUntil: '2026-10-26T12:01:00Z' },
        now,
      ),
    ).toBe(false)
    expect(
      dlqApi.canReplayDlqEvent(
        { ...mockEvent, status: 'REPLAYING', replayClaimUntil: '2026-10-26T11:59:00Z' },
        now,
      ),
    ).toBe(true)
  })
})
