<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import Button from 'primevue/button'
import Badge from 'primevue/badge'
import { useWebSocketStore } from '@/stores/useWebSocketStore'
import type { LiveEvent } from '@/stores/useWebSocketStore'

const store = useWebSocketStore()
const feedContainer = ref<HTMLElement | null>(null)

function relativeTime(date: Date): string {
  const seconds = Math.floor((Date.now() - date.getTime()) / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ago`
}

function eventIcon(event: LiveEvent): string {
  return event.type === 'ORDER' ? '🛒' : '⚠️'
}

watch(
  () => store.events.length,
  () => {
    if (!store.isPaused) {
      nextTick(() => {
        if (feedContainer.value) {
          feedContainer.value.scrollTop = 0
        }
      })
    }
  },
)

watch(
  () => store.isPaused,
  (paused) => {
    if (!paused) {
      nextTick(() => {
        if (feedContainer.value) {
          feedContainer.value.scrollTop = 0
        }
      })
    }
  },
)
</script>

<template>
  <div class="live-feed">
    <div class="live-feed__header">
      <span class="live-feed__title">Live Operations Feed</span>
      <div class="live-feed__controls">
        <Badge
          v-if="store.isPaused && store.newEventCount > 0"
          :value="store.newEventCount"
          severity="danger"
          class="live-feed__badge"
        />
        <Button
          :label="store.isPaused ? 'Resume' : 'Pause'"
          :icon="store.isPaused ? 'pi pi-play' : 'pi pi-pause'"
          size="small"
          text
          @click="store.isPaused ? store.resume() : store.pause()"
        />
      </div>
    </div>

    <div ref="feedContainer" class="live-feed__body">
      <div v-if="store.events.length === 0" class="live-feed__empty">
        <i class="pi pi-spin pi-spinner" style="font-size: 1.2rem; color: var(--color-gray-400)" />
        <span>No events yet. Waiting for activity...</span>
      </div>

      <TransitionGroup v-else name="feed-item" tag="ul" class="live-feed__list">
        <li
          v-for="event in store.events"
          :key="event.id"
          class="live-feed__item"
          :class="`live-feed__item--${event.type.toLowerCase()}`"
        >
          <span class="live-feed__icon" role="img" :aria-label="event.type">
            {{ eventIcon(event) }}
          </span>
          <span class="live-feed__description">{{ event.description }}</span>
          <span class="live-feed__time">{{ relativeTime(event.timestamp) }}</span>
        </li>
      </TransitionGroup>
    </div>
  </div>
</template>

<style scoped>
.live-feed {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid var(--color-gray-200);
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.live-feed__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-gray-100);
  flex-shrink: 0;
}

.live-feed__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-gray-800);
}

.live-feed__controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.live-feed__badge {
  font-size: 11px;
}

.live-feed__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.live-feed__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 16px;
  color: var(--color-gray-400);
  font-size: 13px;
}

.live-feed__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.live-feed__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-gray-50);
  font-size: 13px;
  transition: background 150ms ease;
}

.live-feed__item:hover {
  background: var(--color-gray-50);
}

.live-feed__item--inventory {
  border-left: 3px solid #f59e0b;
}

.live-feed__item--order {
  border-left: 3px solid #3b82f6;
}

.live-feed__icon {
  font-size: 16px;
  flex-shrink: 0;
}

.live-feed__description {
  flex: 1;
  color: var(--color-gray-700);
  line-height: 1.4;
}

.live-feed__time {
  font-size: 11px;
  color: var(--color-gray-400);
  white-space: nowrap;
  flex-shrink: 0;
}

/* TransitionGroup animation */
.feed-item-enter-active {
  transition: all 0.3s ease;
}

.feed-item-enter-from {
  opacity: 0;
  transform: translateY(-12px);
}

.feed-item-leave-active {
  display: none;
}
</style>
