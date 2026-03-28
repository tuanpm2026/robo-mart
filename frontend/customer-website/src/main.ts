import './assets/app.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { customerTheme } from '@robo-mart/shared'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
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

app.mount('#app')
