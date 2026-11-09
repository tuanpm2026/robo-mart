import { onMounted, onUnmounted, ref } from 'vue'

/** Follow the viewport without retaining listeners after the component unmounts. */
export function useMediaQuery(query: string) {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia(query) : null
  const matches = ref(media?.matches ?? false)
  const update = () => {
    matches.value = media?.matches ?? false
  }
  onMounted(() => media?.addEventListener('change', update))
  onUnmounted(() => media?.removeEventListener('change', update))
  return matches
}
