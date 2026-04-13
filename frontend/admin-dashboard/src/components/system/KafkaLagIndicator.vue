<script setup lang="ts">
import { computed } from 'vue'
import Chart from 'primevue/chart'
import Tag from 'primevue/tag'

const props = defineProps<{
  consumerGroup: string | null
  currentLag: number | null
  history: number[]
}>()

const lagStatus = computed((): 'healthy' | 'elevated' | 'critical' => {
  if (props.currentLag === null || props.currentLag < 0) return 'healthy'
  if (props.currentLag < 100) return 'healthy'
  if (props.currentLag <= 1000) return 'elevated'
  return 'critical'
})

const tagSeverity = computed((): 'success' | 'warn' | 'danger' => {
  const map: Record<string, 'success' | 'warn' | 'danger'> = {
    healthy: 'success',
    elevated: 'warn',
    critical: 'danger',
  }
  return map[lagStatus.value]
})

const lagColor = computed(() => {
  const map: Record<string, string> = {
    healthy: '#22c55e',
    elevated: '#f59e0b',
    critical: '#ef4444',
  }
  return map[lagStatus.value]
})

const sparklineData = computed(() => ({
  labels: props.history.map((_, i) => i.toString()),
  datasets: [
    {
      data: props.history,
      borderColor: lagColor.value,
      fill: false,
      pointRadius: 0,
      tension: 0.3,
    },
  ],
}))

const sparklineOptions = {
  plugins: { legend: { display: false } },
  scales: { x: { display: false }, y: { display: false } },
  animation: false,
}
</script>

<template>
  <div
    class="kafka-lag"
    :aria-label="`${consumerGroup} consumer lag: ${currentLag ?? 'N/A'} messages, status: ${lagStatus}`"
  >
    <span class="kafka-group">{{ consumerGroup ?? 'No consumer' }}</span>
    <span class="kafka-count">{{ currentLag !== null ? currentLag : 'N/A' }}</span>
    <Chart
      v-if="history.length > 1"
      type="line"
      :data="sparklineData"
      :options="sparklineOptions"
      style="height: 40px; width: 120px"
    />
    <Tag :severity="tagSeverity" :value="lagStatus" />
  </div>
</template>

<style scoped>
.kafka-lag {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 4px 0;
}

.kafka-group {
  font-size: 12px;
  color: var(--color-gray-600);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kafka-count {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-gray-800);
}
</style>
