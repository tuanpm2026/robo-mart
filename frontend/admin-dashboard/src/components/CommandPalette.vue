<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Dialog from 'primevue/dialog'
import AutoComplete from 'primevue/autocomplete'

const router = useRouter()
const isOpen = ref(false)
const query = ref('')
const inputRef = ref()

interface CommandItem {
  label: string
  action: () => void | Promise<void>
}

const allCommands: CommandItem[] = [
  { label: 'Go to Dashboard', action: () => router.push('/admin/dashboard') },
  { label: 'Go to Products', action: () => router.push('/admin/products') },
  { label: 'Go to Inventory', action: () => router.push('/admin/inventory') },
  { label: 'Go to Orders', action: () => router.push('/admin/orders') },
]

const filteredCommands = ref<CommandItem[]>([])

function search(event: { query: string }) {
  const q = event.query.toLowerCase()
  filteredCommands.value = q
    ? allCommands.filter((c) => c.label.toLowerCase().includes(q))
    : [...allCommands]
}

function onSelect(event: { value: CommandItem }) {
  Promise.resolve(event.value.action()).catch(() => {})
  isOpen.value = false
  query.value = ''
}

let focusTimer: ReturnType<typeof setTimeout> | undefined

function onDialogShow() {
  focusTimer = setTimeout(() => {
    inputRef.value?.$el?.querySelector('input')?.focus()
  }, 50)
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    isOpen.value = true
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  clearTimeout(focusTimer)
})

function open() {
  isOpen.value = true
}

defineExpose({ open, isOpen })
</script>

<template>
  <Dialog
    v-model:visible="isOpen"
    :modal="true"
    position="top"
    :style="{ width: '560px', marginTop: '80px' }"
    :showHeader="false"
    :dismissableMask="true"
    aria-label="Command palette"
    @show="onDialogShow"
  >
    <AutoComplete
      ref="inputRef"
      v-model="query"
      :suggestions="filteredCommands"
      optionLabel="label"
      placeholder="Type a command..."
      :style="{ width: '100%' }"
      @complete="search"
      @option-select="onSelect"
      dropdown
    />
  </Dialog>
</template>
