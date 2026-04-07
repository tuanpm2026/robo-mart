import axios from 'axios'
import { useAdminAuthStore } from '@/stores/useAdminAuthStore'

const adminClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

adminClient.interceptors.request.use((config) => {
  try {
    const authStore = useAdminAuthStore()
    if (authStore.accessToken) {
      config.headers['Authorization'] = `Bearer ${authStore.accessToken}`
    }
  } catch {
    // Pinia not yet initialized (e.g., unit test environment)
  }
  return config
})

adminClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/admin/unauthorized'
    }
    return Promise.reject(error)
  },
)

export default adminClient
