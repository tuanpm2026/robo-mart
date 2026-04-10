import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchDlqEvents, retryDlqEvent, retryAllDlqEvents } from '@/api/dlqApi'
import type { DlqEvent } from '@/api/dlqApi'

export const useDlqStore = defineStore('dlq', () => {
  const events = ref<DlqEvent[]>([])
  const totalElements = ref(0)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function loadEvents(page = 0) {
    isLoading.value = true
    error.value = null
    try {
      const response = await fetchDlqEvents(page, 25)
      events.value = response.data
      totalElements.value = response.pagination.totalElements
    } catch {
      error.value = 'Failed to load DLQ events'
    } finally {
      isLoading.value = false
    }
  }

  async function retryEvent(id: number) {
    await retryDlqEvent(id)
    await loadEvents()
  }

  async function retryAll() {
    await retryAllDlqEvents()
    await loadEvents()
  }

  return {
    events,
    totalElements,
    isLoading,
    error,
    loadEvents,
    retryEvent,
    retryAll,
  }
})
