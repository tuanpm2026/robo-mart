<script setup lang="ts">
import { ref } from 'vue'
import { useToast } from 'primevue/usetoast'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import ProgressBar from 'primevue/progressbar'
import { useDlqStore } from '@/stores/useDlqStore'

const store = useDlqStore()
const toast = useToast()
const expandedRows = ref({})
const retryProgress = ref(0)
const isRetryingAll = ref(false)

function statusSeverity(status: string): 'warn' | 'success' | 'danger' | undefined {
  if (status === 'PENDING') return 'warn'
  if (status === 'RESOLVED') return 'success'
  if (status === 'FAILED_RETRY') return 'danger'
  return undefined
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString()
}

async function retry(id: number) {
  try {
    await store.retryEvent(id)
    toast.add({ severity: 'success', summary: 'Event processed', life: 3000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Still failing — investigate', life: 3000 })
  }
}

async function retryAll() {
  isRetryingAll.value = true
  retryProgress.value = 0
  try {
    const total = store.events.filter((e) => e.status === 'PENDING').length
    retryProgress.value = 50
    await store.retryAll()
    retryProgress.value = 100
    toast.add({
      severity: 'success',
      summary: 'Retry All Complete',
      detail: `${total} events processed`,
      life: 3000,
    })
  } catch {
    toast.add({ severity: 'error', summary: 'Retry All failed', life: 3000 })
  } finally {
    isRetryingAll.value = false
    retryProgress.value = 0
  }
}
</script>

<template>
  <div class="dlq-manager">
    <div class="dlq-manager__header">
      <h2 class="dlq-manager__title">Unprocessed Events</h2>
      <Button
        label="Retry All"
        icon="pi pi-refresh"
        :loading="isRetryingAll"
        :disabled="store.events.filter((e) => e.status === 'PENDING').length === 0"
        @click="retryAll"
      />
    </div>

    <ProgressBar v-if="isRetryingAll" :value="retryProgress" class="dlq-manager__progress" />

    <div v-if="store.error" class="dlq-manager__error">{{ store.error }}</div>

    <DataTable
      v-if="store.events.length > 0"
      :value="store.events"
      :loading="store.isLoading"
      v-model:expandedRows="expandedRows"
      data-key="id"
    >
      <Column expander style="width: 3rem" />
      <Column field="eventType" header="Event Type" />
      <Column field="aggregateId" header="Aggregate ID" />
      <Column field="errorMessage" header="Error Reason" />
      <Column field="firstFailedAt" header="Timestamp">
        <template #body="{ data }">{{ formatDate(data.firstFailedAt) }}</template>
      </Column>
      <Column field="retryCount" header="Retry Count" />
      <Column field="status" header="Status">
        <template #body="{ data }">
          <Tag :severity="statusSeverity(data.status)" :value="data.status" />
        </template>
      </Column>
      <Column header="Actions">
        <template #body="{ data }">
          <Button
            label="Retry"
            size="small"
            :disabled="data.status !== 'PENDING'"
            @click="retry(data.id)"
          />
        </template>
      </Column>
      <template #expansion="{ data }">
        <div class="dlq-detail">
          <p><strong>Error Class:</strong> {{ data.errorClass }}</p>
          <p><strong>Original Topic:</strong> {{ data.originalTopic }}</p>
          <pre>{{ data.payloadPreview }}</pre>
        </div>
      </template>
    </DataTable>

    <div v-else-if="!store.isLoading" class="dlq-manager__empty">
      No unprocessed events — all events processed successfully
    </div>
  </div>
</template>

<style scoped>
.dlq-manager {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dlq-manager__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dlq-manager__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-gray-800);
  margin: 0;
}

.dlq-manager__progress {
  margin-bottom: 8px;
}

.dlq-manager__error {
  color: var(--color-error-600);
  padding: 12px;
  background: var(--color-error-50);
  border-radius: 4px;
}

.dlq-manager__empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--color-gray-500);
  background: var(--color-gray-50);
  border-radius: 8px;
  border: 1px solid var(--color-gray-200);
}

.dlq-detail {
  padding: 12px 16px;
  background: var(--color-gray-50);
}

.dlq-detail pre {
  margin: 8px 0 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
