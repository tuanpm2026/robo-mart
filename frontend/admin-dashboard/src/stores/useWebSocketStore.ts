import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface LiveEvent {
  id: string
  type: 'ORDER' | 'INVENTORY'
  description: string
  raw: Record<string, unknown>
  timestamp: Date
}

const MAX_EVENTS = 100

export const useWebSocketStore = defineStore('websocket', () => {
  const connectionStatus = ref<'connected' | 'reconnecting' | 'disconnected'>('disconnected')
  const events = ref<LiveEvent[]>([])
  const isPaused = ref(false)
  const newEventCount = ref(0)

  function addEvent(event: LiveEvent) {
    events.value.unshift(event)
    if (events.value.length > MAX_EVENTS) {
      events.value.splice(MAX_EVENTS)
    }
    if (isPaused.value) {
      newEventCount.value++
    }
  }

  function pause() {
    isPaused.value = true
  }

  function resume() {
    isPaused.value = false
    newEventCount.value = 0
  }

  function setConnectionStatus(status: 'connected' | 'reconnecting' | 'disconnected') {
    connectionStatus.value = status
  }

  return {
    connectionStatus,
    events,
    isPaused,
    newEventCount,
    addEvent,
    pause,
    resume,
    setConnectionStatus,
  }
})
