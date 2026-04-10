import adminClient from './adminClient'

export interface TopProductEntry {
  productId: number
  productName: string
  totalQuantity: number
  totalRevenue: number
}

export interface RevenueByProductEntry {
  productName: string
  totalRevenue: number
}

export interface OrderTrendEntry {
  date: string
  status: string
  count: number
}

export interface ReportSummary {
  topProducts: TopProductEntry[]
  revenueByProduct: RevenueByProductEntry[]
  orderTrends: OrderTrendEntry[]
}

export async function fetchReportSummary(from: string, to: string): Promise<ReportSummary> {
  const { data } = await adminClient.get<{ data: ReportSummary; traceId: string }>(
    '/api/v1/admin/reports/summary',
    { params: { from, to } },
  )
  return data.data
}

export async function triggerRebuild(): Promise<string> {
  const { data } = await adminClient.post<{ data: string; traceId: string }>(
    '/api/v1/admin/reports/rebuild',
  )
  return data.data
}
