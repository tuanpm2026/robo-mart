<script setup lang="ts">
import { ref, computed } from 'vue'
import KafkaLagIndicator from './KafkaLagIndicator.vue'
import { computeVisualStatus } from '@/stores/useSystemHealthStore'
import type { ServiceHealthData } from '@/api/systemHealthApi'

const props = defineProps<{
  service: ServiceHealthData
  history: number[]
}>()

const isExpanded = ref(false)

const visualStatus = computed(() => computeVisualStatus(props.service))

const statusClass = computed(() => {
  const map: Record<string, string> = {
    healthy: 'health-healthy',
    degraded: 'health-degraded',
    down: 'health-down',
  }
  return map[visualStatus.value]
})

const statusIcon = computed(() => {
  const map: Record<string, string> = {
    healthy: 'pi pi-check-circle',
    degraded: 'pi pi-exclamation-circle',
    down: 'pi pi-times-circle',
  }
  return map[visualStatus.value]
})

const p95Label = computed(() =>
  props.service.p95ResponseTimeMs !== null ? props.service.p95ResponseTimeMs + 'ms' : 'N/A',
)

const cpuLabel = computed(() =>
  props.service.cpuPercent !== null ? props.service.cpuPercent.toFixed(1) + '%' : 'N/A',
)

const memoryLabel = computed(() =>
  props.service.memoryPercent !== null ? props.service.memoryPercent.toFixed(1) + '%' : 'N/A',
)
</script>

<template>
  <div
    class="service-card"
    :class="statusClass"
    role="article"
    :aria-label="`${service.displayName} status: ${visualStatus}`"
  >
    <!-- Header row (always visible) -->
    <div class="card-header" @click="isExpanded = !isExpanded">
      <i :class="statusIcon" class="status-icon" />
      <span class="service-name">{{ service.displayName }}</span>
      <span class="p95">{{ p95Label }}</span>
      <i class="pi pi-chevron-down expand-icon" :class="{ rotated: isExpanded }" />
    </div>

    <!-- Expandable metrics -->
    <Transition name="expand">
      <div v-if="isExpanded" class="card-metrics">
        <div class="metric-row">
          <span>CPU</span>
          <span>{{ cpuLabel }}</span>
        </div>
        <div class="metric-row">
          <span>Memory</span>
          <span>{{ memoryLabel }}</span>
        </div>
        <div v-if="service.dbPoolActive !== null" class="metric-row">
          <span>DB Pool</span>
          <span>{{ service.dbPoolActive }}/{{ service.dbPoolMax ?? '?' }}</span>
        </div>
        <KafkaLagIndicator
          v-if="service.consumerGroup !== null"
          :consumerGroup="service.consumerGroup"
          :currentLag="service.kafkaConsumerLag"
          :history="history"
        />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.service-card {
  background: #ffffff;
  border-radius: 6px;
  border: 1px solid var(--color-gray-200);
  border-left: 4px solid transparent;
  transition: border-left-color 0.5s ease;
  overflow: hidden;
}

.service-card .pi {
  transition: color 0.5s ease;
}

.health-healthy {
  border-left-color: #22c55e;
}

.health-degraded {
  border-left-color: #f59e0b;
}

.health-down {
  border-left-color: #ef4444;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  cursor: pointer;
  user-select: none;
}

.card-header:hover {
  background: var(--color-gray-50);
}

.status-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.health-healthy .status-icon {
  color: #22c55e;
}

.health-degraded .status-icon {
  color: #f59e0b;
}

.health-down .status-icon {
  color: #ef4444;
}

.service-name {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-gray-800);
}

.p95 {
  font-size: 13px;
  color: var(--color-gray-500);
  margin-right: 4px;
}

.expand-icon {
  font-size: 12px;
  color: var(--color-gray-400);
  transition: transform 0.2s ease;
}

.expand-icon.rotated {
  transform: rotate(180deg);
}

.card-metrics {
  padding: 0 14px 12px;
  border-top: 1px solid var(--color-gray-100);
}

.metric-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  font-size: 13px;
  color: var(--color-gray-600);
}

.metric-row span:last-child {
  font-weight: 500;
  color: var(--color-gray-800);
}

/* Expand/collapse transition */
.expand-enter-active,
.expand-leave-active {
  transition:
    max-height 0.2s ease,
    opacity 0.2s ease;
  max-height: 300px;
  overflow: hidden;
}

.expand-enter-from,
.expand-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
