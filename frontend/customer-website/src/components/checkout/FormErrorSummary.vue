<script setup lang="ts">
import { nextTick, ref } from 'vue'
defineProps<{ errors: Record<string, string | undefined> }>()
const summary = ref<HTMLElement | null>(null)
async function focus() {
  await nextTick()
  summary.value?.focus()
}
function focusField(id: string) {
  document.getElementById(id)?.focus()
}
defineExpose({ focus })
</script>
<template>
  <div
    v-if="Object.keys(errors).length"
    ref="summary"
    class="form-error-summary"
    tabindex="-1"
    role="alert"
  >
    <p>Please correct the following fields:</p>
    <ul>
      <li v-for="(error, field) in errors" :key="field">
        <a :href="`#${field}`" @click.prevent="focusField(String(field))">{{ error }}</a>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.form-error-summary {
  padding: 16px;
  border: 1px solid #b91c1c;
  border-radius: 6px;
  color: #991b1b;
  background: #fef2f2;
}
.form-error-summary p {
  margin-top: 0;
  font-weight: 600;
}
.form-error-summary ul {
  margin-bottom: 0;
  padding-left: 20px;
}
.form-error-summary a {
  color: inherit;
}
</style>
