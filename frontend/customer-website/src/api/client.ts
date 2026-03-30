import axios from 'axios'

let cachedUserId: string | null = null

function getUserId(): string {
  if (cachedUserId) return cachedUserId
  try {
    cachedUserId = localStorage.getItem('robomart-user-id')
    if (!cachedUserId) {
      cachedUserId = crypto.randomUUID()
      localStorage.setItem('robomart-user-id', cachedUserId)
    }
  } catch {
    if (!cachedUserId) {
      cachedUserId = 'anon-' + Math.random().toString(36).slice(2, 11)
    }
  }
  return cachedUserId
}

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8081',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  config.headers['X-User-Id'] = getUserId()
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const { status } = error.response
      if (status === 404) {
        return Promise.reject(new Error('Resource not found'))
      }
      if (status >= 500) {
        return Promise.reject(new Error('Server error. Please try again later.'))
      }
    } else if (error.request) {
      return Promise.reject(new Error('Network error. Please check your connection.'))
    }
    return Promise.reject(error)
  },
)

export default apiClient
