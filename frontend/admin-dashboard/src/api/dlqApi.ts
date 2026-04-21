import adminClient from './adminClient'

export interface DlqEvent {
  id: number
  eventType: string
  aggregateId: string
  originalTopic: string
  errorClass: string
  errorMessage: string
  payloadPreview: string
  retryCount: number
  status: string
  firstFailedAt: string
  lastAttemptedAt: string
}

interface PagedResponse<T> {
  data: T[]
  pagination: { page: number; size: number; totalElements: number; totalPages: number }
  traceId: string
}

export async function fetchDlqEvents(page: number, size: number): Promise<PagedResponse<DlqEvent>> {
  const { data } = await adminClient.get<PagedResponse<DlqEvent>>('/api/v1/admin/dlq', {
    params: { page, size },
  })
  return data
}

export async function retryDlqEvent(id: number): Promise<string> {
  const { data } = await adminClient.post<{ data: string; traceId: string }>(
    `/api/v1/admin/dlq/${id}/retry`,
  )
  return data.data
}

export async function retryAllDlqEvents(): Promise<string> {
  const { data } = await adminClient.post<{ data: string; traceId: string }>(
    '/api/v1/admin/dlq/retry-all',
  )
  return data.data
}
