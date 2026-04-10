import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach } from 'vitest'
import { useWebSocketStore } from '@/stores/useWebSocketStore'
import type { LiveEvent } from '@/stores/useWebSocketStore'

function makeEvent(id: string): LiveEvent {
  return { id, type: 'ORDER', description: `Event ${id}`, raw: {}, timestamp: new Date() }
}

describe('useWebSocketStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('addEvent prepends events to the front of the array', () => {
    const store = useWebSocketStore()
    store.addEvent(makeEvent('1'))
    store.addEvent(makeEvent('2'))
    expect(store.events[0].id).toBe('2')
    expect(store.events[1].id).toBe('1')
  })

  it('caps events array at 100 entries', () => {
    const store = useWebSocketStore()
    for (let i = 0; i < 110; i++) {
      store.addEvent(makeEvent(String(i)))
    }
    expect(store.events.length).toBe(100)
  })

  it('increments newEventCount when paused and event added', () => {
    const store = useWebSocketStore()
    store.pause()
    store.addEvent(makeEvent('a'))
    store.addEvent(makeEvent('b'))
    expect(store.newEventCount).toBe(2)
    expect(store.isPaused).toBe(true)
  })

  it('does not increment newEventCount when not paused', () => {
    const store = useWebSocketStore()
    store.addEvent(makeEvent('a'))
    expect(store.newEventCount).toBe(0)
  })

  it('resume resets newEventCount and isPaused', () => {
    const store = useWebSocketStore()
    store.pause()
    store.addEvent(makeEvent('a'))
    store.resume()
    expect(store.newEventCount).toBe(0)
    expect(store.isPaused).toBe(false)
  })

  it('setConnectionStatus updates connectionStatus', () => {
    const store = useWebSocketStore()
    store.setConnectionStatus('connected')
    expect(store.connectionStatus).toBe('connected')
    store.setConnectionStatus('reconnecting')
    expect(store.connectionStatus).toBe('reconnecting')
    store.setConnectionStatus('disconnected')
    expect(store.connectionStatus).toBe('disconnected')
  })
})
