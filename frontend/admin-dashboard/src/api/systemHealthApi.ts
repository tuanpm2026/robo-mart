import adminClient from './adminClient'

export interface ServiceHealthData {
  service: string
  displayName: string
  actuatorStatus: 'UP' | 'DOWN'
  p95ResponseTimeMs: number | null
  cpuPercent: number | null
  memoryPercent: number | null
  dbPoolActive: number | null
  dbPoolMax: number | null
  kafkaConsumerLag: number | null
  consumerGroup: string | null
  checkedAt: string
}

export interface SystemHealthResponse {
  services: ServiceHealthData[]
  checkedAt: string
}

export async function fetchSystemHealth(): Promise<SystemHealthResponse> {
  const { data } = await adminClient.get<{ data: SystemHealthResponse; traceId: string }>(
    '/api/v1/admin/system/health',
  )
  return data.data
}
