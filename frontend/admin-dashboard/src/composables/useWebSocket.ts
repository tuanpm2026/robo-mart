import type { Client } from '@stomp/stompjs'
import { useToast } from 'primevue/usetoast'
import { createWebSocketClient } from '@/api/websocketClient'
import { useWebSocketStore } from '@/stores/useWebSocketStore'
import { useAdminAuthStore } from '@/stores/useAdminAuthStore'
import type { LiveEvent } from '@/stores/useWebSocketStore'

let stompClient: Client | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

export function useWebSocket() {
  const store = useWebSocketStore()
  const authStore = useAdminAuthStore()
  const toast = useToast()

  function subscribeToTopics() {
    if (!stompClient) return

    stompClient.subscribe('/topic/orders', (message) => {
      try {
        const payload = JSON.parse(message.body) as Record<string, unknown>
        const event: LiveEvent = {
          id: `${payload.orderId as string}-${Date.now()}`,
          type: 'ORDER',
          description: `Order #${payload.orderId as string} → ${payload.status as string}`,
          raw: payload,
          timestamp: new Date(),
        }
        store.addEvent(event)
      } catch (e) {
        console.error('Failed to parse order WebSocket event', e)
      }
    })

    stompClient.subscribe('/topic/inventory-alerts', (message) => {
      try {
        const payload = JSON.parse(message.body) as Record<string, unknown>
        const event: LiveEvent = {
          id: `${payload.productId as string}-alert-${Date.now()}`,
          type: 'INVENTORY',
          description: `Low stock: ${payload.productName as string} (${payload.currentStock as number} left)`,
          raw: payload,
          timestamp: new Date(),
        }
        store.addEvent(event)
      } catch (e) {
        console.error('Failed to parse inventory WebSocket event', e)
      }
    })
  }

  function connect() {
    const token = authStore.accessToken
    if (!token) {
      console.warn('No access token available — WebSocket connection skipped')
      return
    }

    stompClient = createWebSocketClient(token)
    let hasConnectedBefore = false

    stompClient.onConnect = () => {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }

      if (hasConnectedBefore) {
        toast.add({ severity: 'success', summary: 'Connection restored', life: 3000 })
      }
      hasConnectedBefore = true

      store.setConnectionStatus('connected')
      subscribeToTopics()
    }

    stompClient.onDisconnect = () => {
      store.setConnectionStatus('disconnected')
      reconnectTimer = setTimeout(() => {
        if (store.connectionStatus !== 'connected') {
          store.setConnectionStatus('reconnecting')
          toast.add({ severity: 'warn', summary: 'Reconnecting...', detail: 'Connection lost. Attempting to reconnect.', life: 5000 })
        }
      }, 5000)
    }

    stompClient.onStompError = (frame) => {
      console.error('STOMP protocol error', frame)
    }

    stompClient.activate()
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (stompClient) {
      stompClient.deactivate()
      stompClient = null
    }
    store.setConnectionStatus('disconnected')
  }

  return { connect, disconnect }
}
