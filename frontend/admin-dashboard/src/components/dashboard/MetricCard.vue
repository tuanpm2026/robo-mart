<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import Card from 'primevue/card'
import Skeleton from 'primevue/skeleton'

const props = defineProps<{
  label: string
  value: number | string
  format: 'number' | 'currency' | 'label'
  color: 'blue' | 'green' | 'yellow' | 'red'
  loading: boolean
}>()

const colorMap: Record<string, string> = {
  blue: '#3b82f6',
  green: '#22c55e',
  yellow: '#f59e0b',
  red: '#ef4444',
}

const displayValue = ref(0)
let rafId: number | null = null

watch(
  () => props.value,
  (newVal) => {
    if (props.loading || props.format === 'label') return
    const numVal = newVal as number
    const startVal = displayValue.value
    const startTime = performance.now()
    const duration = 800

    function animate(now: number) {
      const elapsed = now - startTime
      const progress = Math.min(elapsed / duration, 1)
      const ease = 1 - Math.pow(1 - progress, 3)
      const raw = startVal + (numVal - startVal) * ease
      displayValue.value = props.format === 'number' ? Math.round(raw) : raw
      if (progress < 1) {
        rafId = requestAnimationFrame(animate)
      }
    }
    if (rafId) cancelAnimationFrame(rafId)
    rafId = requestAnimationFrame(animate)
  },
  { immediate: true },
)

onUnmounted(() => {
  if (rafId) cancelAnimationFrame(rafId)
})

function formatValue(val: number): string {
  if (props.format === 'currency') {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val)
  }
  return String(val)
}
</script>

<template>
  <Card class="metric-card" :style="{ borderLeft: `4px solid ${colorMap[color]}` }">
    <template #content>
      <div v-if="loading">
        <Skeleton width="100%" height="80px" border-radius="8px" />
      </div>
      <div v-else class="metric-content">
        <div class="metric-label">{{ label }}</div>
        <div class="metric-value" :style="{ color: colorMap[color] }">
          {{ format === 'label' ? String(value) : formatValue(displayValue) }}
        </div>
      </div>
    </template>
  </Card>
</template>

<style scoped>
.metric-card {
  border-radius: 8px;
}

.metric-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metric-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-gray-600, #4b5563);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}
</style>
