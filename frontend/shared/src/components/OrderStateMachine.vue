<script setup lang="ts">
import { computed } from 'vue'

export type OrderStatus =
  | 'PENDING'
  | 'INVENTORY_RESERVING'
  | 'PAYMENT_PROCESSING'
  | 'CONFIRMED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'PAYMENT_REFUNDING'
  | 'INVENTORY_RELEASING'
  | 'CANCELLED'

export interface OrderStatusHistoryEntry {
  status: OrderStatus
  changedAt: string
}

interface Props {
  status: OrderStatus
  statusHistory?: OrderStatusHistoryEntry[]
  cancellationReason?: string
}

const props = defineProps<Props>()

const STEPS: { label: string; statuses: OrderStatus[] }[] = [
  { label: 'Order received', statuses: ['PENDING', 'INVENTORY_RESERVING'] },
  { label: 'Processing payment', statuses: ['PAYMENT_PROCESSING', 'PAYMENT_REFUNDING', 'INVENTORY_RELEASING'] },
  { label: 'Order confirmed', statuses: ['CONFIRMED'] },
  { label: 'Shipped', statuses: ['SHIPPED'] },
  { label: 'Delivered', statuses: ['DELIVERED'] },
]

function statusToStepIndex(s: OrderStatus): number {
  switch (s) {
    case 'PENDING':
    case 'INVENTORY_RESERVING':
      return 0
    case 'PAYMENT_PROCESSING':
    case 'PAYMENT_REFUNDING':
    case 'INVENTORY_RELEASING':
      return 1
    case 'CONFIRMED':
      return 2
    case 'SHIPPED':
      return 3
    case 'DELIVERED':
      return 4
    default:
      return 0
  }
}

const isCancelled = computed(() => props.status === 'CANCELLED')

const currentStepIndex = computed<number>(() => {
  if (!isCancelled.value) {
    return statusToStepIndex(props.status)
  }
  // For CANCELLED: find last non-cancelled status in history
  const history = props.statusHistory ?? []
  for (let i = history.length - 1; i >= 0; i--) {
    const entry = history[i]!
    if (entry.status !== 'CANCELLED') {
      return statusToStepIndex(entry.status)
    }
  }
  return 0
})

const isDelivered = computed(() => props.status === 'DELIVERED')

/** Find the earliest changedAt for a given step from statusHistory. */
function getTimestampForStep(stepIndex: number): string | undefined {
  const history = props.statusHistory ?? []
  const relevantStatuses = STEPS[stepIndex]!.statuses
  // Use the first occurrence of any status in this step
  const entry = history.find((e) => relevantStatuses.includes(e.status))
  return entry?.changedAt
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const month = months[d.getMonth()]!
  const day = d.getDate()
  const year = d.getFullYear()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${month} ${day}, ${year} ${hh}:${mm}`
}

type StepState = 'completed' | 'active' | 'cancelled' | 'upcoming'

function getStepState(stepIndex: number): StepState {
  if (isCancelled.value && stepIndex === currentStepIndex.value) return 'cancelled'
  if (isDelivered.value || stepIndex < currentStepIndex.value) return 'completed'
  if (stepIndex === currentStepIndex.value) return 'active'
  return 'upcoming'
}
</script>

<template>
  <div
    class="osm"
    role="progressbar"
    :aria-valuenow="currentStepIndex + 1"
    aria-valuemin="1"
    aria-valuemax="5"
    :aria-valuetext="STEPS[currentStepIndex]?.label ?? ''"
  >
    <div class="osm__track">
      <template v-for="(step, index) in STEPS" :key="step.label">
        <!-- Connector line (before each step except the first) -->
        <div
          v-if="index > 0"
          class="osm__connector"
          :class="{
            'osm__connector--completed': getStepState(index) === 'completed' || getStepState(index - 1) === 'completed',
          }"
        />

        <!-- Step -->
        <div class="osm__step" :class="`osm__step--${getStepState(index)}`">
          <!-- Circle -->
          <div
            class="osm__circle"
            :class="`osm__circle--${getStepState(index)}`"
            :title="getStepState(index) === 'cancelled' && cancellationReason ? cancellationReason : undefined"
          >
            <span v-if="getStepState(index) === 'completed'" class="osm__icon" aria-hidden="true">✓</span>
            <span v-else-if="getStepState(index) === 'cancelled'" class="osm__icon" aria-hidden="true">✗</span>
            <span v-else class="osm__dot" aria-hidden="true" />
          </div>

          <!-- Label + timestamp wrapper (used by mobile layout) -->
          <div class="osm__step-text">
            <span class="osm__label" :class="`osm__label--${getStepState(index)}`">
              {{ step.label }}
            </span>
            <span
              v-if="getStepState(index) === 'completed' && getTimestampForStep(index)"
              class="osm__timestamp"
            >
              {{ formatDate(getTimestampForStep(index)!) }}
            </span>
          </div>
        </div>
      </template>
    </div>

    <!-- Cancellation reason banner -->
    <div v-if="isCancelled && cancellationReason" class="osm__cancellation-banner">
      <span class="osm__cancellation-icon" aria-hidden="true">✗</span>
      Order cancelled: {{ cancellationReason }}
    </div>
  </div>
</template>

<style scoped>
/* ── Layout ────────────────────────────────────────────────── */
.osm {
  width: 100%;
  padding: 24px 16px;
  box-sizing: border-box;
}

.osm__track {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: center;
  gap: 0;
  width: 100%;
}

/* ── Connector line ────────────────────────────────────────── */
.osm__connector {
  flex: 1;
  height: 2px;
  background-color: var(--p-text-muted-color, #6b7280);
  margin-top: 20px; /* align with center of circle (40px circle / 2) */
  opacity: 0.3;
  min-width: 16px;
}

.osm__connector--completed {
  background-color: var(--p-green-500, #22c55e);
  opacity: 1;
}

/* ── Step wrapper ──────────────────────────────────────────── */
.osm__step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  width: 80px;
}

/* ── Circle ────────────────────────────────────────────────── */
.osm__circle {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid transparent;
  box-sizing: border-box;
  position: relative;
  transition: border-color 200ms, background-color 200ms;
}

.osm__circle--upcoming {
  background-color: transparent;
  border-color: var(--p-text-muted-color, #6b7280);
}

.osm__circle--active {
  background-color: transparent;
  border-color: var(--p-primary-color, #2563eb);
  animation: pulse-ring 1.6s ease-in-out infinite;
}

.osm__circle--completed {
  background-color: var(--p-green-500, #22c55e);
  border-color: var(--p-green-500, #22c55e);
}

.osm__circle--cancelled {
  background-color: var(--p-red-500, #ef4444);
  border-color: var(--p-red-500, #ef4444);
  cursor: help;
}

/* ── Icons inside circle ──────────────────────────────────── */
.osm__icon {
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
  user-select: none;
}

.osm__dot {
  display: block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--p-text-muted-color, #6b7280);
}

.osm__circle--active .osm__dot {
  background-color: var(--p-primary-color, #2563eb);
}

/* ── Labels ────────────────────────────────────────────────── */
.osm__label {
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
  color: var(--p-text-muted-color, #6b7280);
  transition: color 200ms, font-weight 200ms;
  word-break: break-word;
}

.osm__label--active {
  font-weight: 700;
  color: var(--p-primary-color, #2563eb);
}

.osm__label--completed {
  color: var(--p-green-500, #22c55e);
}

.osm__label--cancelled {
  font-weight: 700;
  color: var(--p-red-500, #ef4444);
}

/* ── Timestamps ────────────────────────────────────────────── */
.osm__timestamp {
  font-size: 10px;
  color: var(--p-text-muted-color, #6b7280);
  text-align: center;
  line-height: 1.3;
}

/* ── Cancellation banner ───────────────────────────────────── */
.osm__cancellation-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  padding: 10px 14px;
  background-color: color-mix(in srgb, var(--p-red-500, #ef4444) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--p-red-500, #ef4444) 30%, transparent);
  border-radius: 8px;
  font-size: 13px;
  color: var(--p-red-500, #ef4444);
}

.osm__cancellation-icon {
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}

/* ── Pulse animation ───────────────────────────────────────── */
@keyframes pulse-ring {
  0%, 100% {
    box-shadow: 0 0 0 0 var(--p-primary-color, #2563eb);
    opacity: 0.7;
  }
  50% {
    box-shadow: 0 0 0 6px transparent;
    opacity: 1;
  }
}

/* ── Reduced motion ────────────────────────────────────────── */
@media (prefers-reduced-motion: reduce) {
  .osm__circle--active {
    animation: none;
  }
  .osm__circle,
  .osm__label {
    transition: none;
  }
}

/* ── Mobile: vertical stack ────────────────────────────────── */
@media (max-width: 600px) {
  .osm__track {
    flex-direction: column;
    align-items: flex-start;
    gap: 0;
    padding-left: 20px;
  }

  .osm__connector {
    width: 2px;
    height: 24px;
    min-width: unset;
    margin-top: 0;
    margin-left: 19px; /* (40px circle / 2) - (2px / 2) - 1px for border */
    flex: none;
  }

  .osm__step {
    flex-direction: row;
    align-items: flex-start;
    width: 100%;
    gap: 12px;
  }

  .osm__circle {
    flex-shrink: 0;
  }

  .osm__label,
  .osm__timestamp {
    text-align: left;
  }

  .osm__step-text {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
