import './assets/app.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { adminTheme } from '@robo-mart/shared'

import App from './App.vue'
import router from './router'
import { useAdminAuthStore } from './stores/useAdminAuthStore'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: adminTheme,
    options: {
      darkModeSelector: false,
    },
  },
})
app.use(ToastService)

const adminAuthStore = useAdminAuthStore()
adminAuthStore.initAuth().finally(() => {
  app.mount('#app')
})
