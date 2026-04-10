import { ref } from 'vue'
import { defineStore } from 'pinia'
import { useToast } from 'primevue/usetoast'
import { fetchReportSummary, triggerRebuild } from '@/api/reportsApi'
import type { ReportSummary } from '@/api/reportsApi'

function startOfTodayUTC(): string {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  return d.toISOString()
}

function nowUTC(): string {
  return new Date().toISOString()
}

export const useReportsStore = defineStore('reports', () => {
  const toast = useToast()
  const summary = ref<ReportSummary | null>(null)
  const isLoading = ref(false)
  const isRebuilding = ref(false)
  const error = ref<string | null>(null)
  const dateRange = ref({ from: startOfTodayUTC(), to: nowUTC() })

  async function loadSummary() {
    isLoading.value = true
    error.value = null
    try {
      summary.value = await fetchReportSummary(dateRange.value.from, dateRange.value.to)
    } catch {
      error.value = 'Failed to load report summary'
    } finally {
      isLoading.value = false
    }
  }

  async function rebuild() {
    isRebuilding.value = true
    try {
      const message = await triggerRebuild()
      toast.add({ severity: 'success', summary: 'Rebuild initiated', detail: message, life: 3000 })
      await loadSummary()
    } catch {
      toast.add({ severity: 'error', summary: 'Rebuild failed', life: 3000 })
    } finally {
      isRebuilding.value = false
    }
  }

  function setDateRange(from: string, to: string) {
    dateRange.value = { from, to }
    loadSummary()
  }

  return {
    summary,
    isLoading,
    isRebuilding,
    error,
    dateRange,
    loadSummary,
    rebuild,
    setDateRange,
  }
})
