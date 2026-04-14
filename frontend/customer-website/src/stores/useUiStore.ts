import { ref } from 'vue'
import { defineStore } from 'pinia'

export type DegradationTier = 'normal' | 'partial' | 'maintenance'

export const useUiStore = defineStore('ui', () => {
  const degradationTier = ref<DegradationTier>('normal')
  const isBannerDismissed = ref(
    sessionStorage.getItem('degradation-banner-dismissed') === 'true'
  )

  function setDegradationTier(tier: DegradationTier) {
    // Once maintenance is set, it cannot be downgraded by partial signals
    if (tier === 'maintenance' || degradationTier.value !== 'maintenance') {
      degradationTier.value = tier
    }
  }

  function dismissBanner() {
    isBannerDismissed.value = true
    sessionStorage.setItem('degradation-banner-dismissed', 'true')
  }

  function resetDegradation() {
    degradationTier.value = 'normal'
    isBannerDismissed.value = false
    sessionStorage.removeItem('degradation-banner-dismissed')
  }

  return {
    degradationTier,
    isBannerDismissed,
    setDegradationTier,
    dismissBanner,
    resetDegradation,
  }
})
