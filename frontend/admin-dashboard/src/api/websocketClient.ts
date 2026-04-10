import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

export function createWebSocketClient(token: string): Client {
  const apiUrl = (import.meta.env.VITE_API_URL as string | undefined) || 'http://localhost:8080'
  return new Client({
    webSocketFactory: () => new SockJS(`${apiUrl}/ws`),
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,
  })
}
