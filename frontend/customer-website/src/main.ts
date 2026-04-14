import './assets/app.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { customerTheme } from '@robo-mart/shared'

import App from './App.vue'
import router from './router'
import { setAuthAccessor, setUiAccessor } from './api/client'
import { useAuthStore } from './stores/useAuthStore'
import { useUiStore } from './stores/useUiStore'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: customerTheme,
    options: {
      darkModeSelector: false,
    },
  },
})
app.use(ToastService)

// Wire auth store into API client (avoids circular import)
const authStore = useAuthStore()
setAuthAccessor(() => ({
  accessToken: authStore.accessToken,
  userId: authStore.user?.id || null,
  refreshToken: () => authStore.refreshToken(),
  logout: () => authStore.logout(),
}))

// Wire UI store into API client for degradation tier detection
const uiStore = useUiStore()
setUiAccessor(() => uiStore)

// Initialize auth state before mounting (restore session from storage)
authStore.initAuth().finally(() => {
  app.mount('#app')
})
