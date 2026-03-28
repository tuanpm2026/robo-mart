<script setup lang="ts">
export interface EmptyStateProps {
  variant?: 'search-results' | 'cart' | 'orders' | 'generic'
  title?: string
  description?: string
  ctaLabel?: string
}

const props = withDefaults(defineProps<EmptyStateProps>(), {
  variant: 'generic',
})

const emit = defineEmits<{
  action: []
}>()

const defaults: Record<string, { title: string; description: string; ctaLabel: string }> = {
  'search-results': {
    title: 'No results found',
    description: 'Try different keywords or filters',
    ctaLabel: 'Clear Filters',
  },
  cart: {
    title: 'Your cart is empty',
    description: 'Browse products and add items to your cart',
    ctaLabel: 'Browse Products',
  },
  orders: {
    title: 'No orders yet',
    description: 'Start shopping to see your orders here',
    ctaLabel: 'Browse Products',
  },
  generic: {
    title: 'Nothing here yet',
    description: 'Check back later for updates',
    ctaLabel: 'Go Home',
  },
}

const config = defaults[props.variant] ?? defaults.generic!
const displayTitle = props.title ?? config.title
const displayDescription = props.description ?? config.description
const displayCtaLabel = props.ctaLabel ?? config.ctaLabel
</script>

<template>
  <div class="empty-state" role="status">
    <svg
      class="empty-state__illustration"
      aria-hidden="true"
      width="120"
      height="120"
      viewBox="0 0 120 120"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <circle cx="60" cy="60" r="50" stroke="currentColor" stroke-width="2" opacity="0.2" />
      <path
        d="M40 55 L55 70 L80 45"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
        opacity="0.3"
        v-if="variant === 'generic' || variant === 'orders'"
      />
      <circle
        cx="60"
        cy="52"
        r="14"
        stroke="currentColor"
        stroke-width="2"
        opacity="0.3"
        v-if="variant === 'search-results'"
      />
      <path
        d="M70 62 L80 72"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        opacity="0.3"
        v-if="variant === 'search-results'"
      />
      <path
        d="M45 50 L60 70 L75 50 Z"
        stroke="currentColor"
        stroke-width="2"
        fill="none"
        opacity="0.3"
        v-if="variant === 'cart'"
      />
    </svg>
    <h3 class="empty-state__title">{{ displayTitle }}</h3>
    <p class="empty-state__description">{{ displayDescription }}</p>
    <button class="empty-state__cta" type="button" @click="emit('action')">
      {{ displayCtaLabel }}
    </button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  text-align: center;
  gap: 12px;
}

.empty-state__illustration {
  color: var(--p-primary-500, #3b82f6);
  margin-bottom: 8px;
}

.empty-state__title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--p-text-color, #111827);
  margin: 0;
}

.empty-state__description {
  font-size: 14px;
  font-weight: 400;
  line-height: 1.5;
  color: var(--p-text-muted-color, #4b5563);
  margin: 0;
  max-width: 320px;
}

.empty-state__cta {
  margin-top: 8px;
  padding: 10px 24px;
  min-height: 40px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
  color: var(--p-primary-contrast-color, #ffffff);
  background: var(--p-primary-color, #2563eb);
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 200ms;
}

.empty-state__cta:hover {
  background: var(--p-primary-hover-color, #1d4ed8);
}

.empty-state__cta:focus-visible {
  outline: 2px solid var(--p-primary-500, #3b82f6);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .empty-state__cta {
    transition: none;
  }
}
</style>
