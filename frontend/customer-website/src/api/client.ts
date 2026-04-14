import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'

let cachedAnonymousId: string | null = null

export function clearAnonymousIdCache(): void {
  cachedAnonymousId = null
}

export function getAnonymousUserId(): string {
  if (cachedAnonymousId) return cachedAnonymousId
  try {
    cachedAnonymousId = localStorage.getItem('robomart-user-id')
    if (!cachedAnonymousId) {
      cachedAnonymousId = crypto.randomUUID()
      localStorage.setItem('robomart-user-id', cachedAnonymousId)
    }
  } catch {
    if (!cachedAnonymousId) {
      cachedAnonymousId = 'anon-' + Math.random().toString(36).slice(2, 11)
    }
  }
  return cachedAnonymousId
}

// Auth store accessor — set by main.ts after store initialization to avoid circular imports
let getAuthState: (() => { accessToken: string | null; userId: string | null; refreshToken: () => Promise<boolean>; logout: () => Promise<void> }) | null = null

export function setAuthAccessor(
  accessor: () => { accessToken: string | null; userId: string | null; refreshToken: () => Promise<boolean>; logout: () => Promise<void> },
): void {
  getAuthState = accessor
}

// UI store accessor — set by main.ts after store initialization
let getUiState: (() => { setDegradationTier: (tier: 'normal' | 'partial' | 'maintenance') => void }) | null = null

export function setUiAccessor(
  accessor: () => { setDegradationTier: (tier: 'normal' | 'partial' | 'maintenance') => void },
): void {
  getUiState = accessor
}

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  // Skip header injection for retried requests — they already have fresh tokens
  if ((config as InternalAxiosRequestConfig & { _retried?: boolean })._retried) return config

  const auth = getAuthState?.()
  if (auth?.accessToken) {
    config.headers['Authorization'] = `Bearer ${auth.accessToken}`
    config.headers['X-User-Id'] = auth.userId || getAnonymousUserId()
  } else {
    config.headers['X-User-Id'] = getAnonymousUserId()
  }
  return config
})

// Mutex for concurrent token refresh and logout
let refreshPromise: Promise<boolean> | null = null
let logoutInProgress = false

apiClient.interceptors.response.use(
  (response) => {
    // On any successful response, attempt to recover partial degradation tier back to normal.
    // The setDegradationTier guard prevents accidentally downgrading from 'maintenance' to 'normal'
    // (maintenance requires a page reload), so only 'partial' tier auto-recovers here.
    try {
      getUiState?.().setDegradationTier('normal')
    } catch {
      // Ignore store errors — degradation tier is non-critical
    }
    return response
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retried?: boolean }

    if (error.response?.status === 401 && !originalRequest._retried) {
      const auth = getAuthState?.()
      if (auth) {
        originalRequest._retried = true

        // Use mutex to prevent concurrent refresh requests
        if (!refreshPromise) {
          refreshPromise = auth.refreshToken().finally(() => {
            refreshPromise = null
          })
        }

        const refreshed = await refreshPromise
        if (refreshed) {
          const freshAuth = getAuthState?.()
          if (freshAuth?.accessToken) {
            originalRequest.headers['Authorization'] = `Bearer ${freshAuth.accessToken}`
          }
          return apiClient(originalRequest)
        }

        // Refresh failed — log out (with mutex to prevent concurrent logouts)
        if (!logoutInProgress) {
          logoutInProgress = true
          await auth.logout().finally(() => { logoutInProgress = false })
        }
        return Promise.reject(new Error('Session expired. Please log in again.'))
      }
    }

    if (error.response) {
      const { status } = error.response
      if (status === 503) {
        try {
          getUiState?.().setDegradationTier('partial')
        } catch {
          // Ignore store errors
        }
        return Promise.reject(new Error('Service temporarily unavailable'))
      }
      if (status === 404) {
        return Promise.reject(new Error('Resource not found'))
      }
      if (status >= 500) {
        return Promise.reject(new Error('Server error. Please try again later.'))
      }
    } else if (error.request) {
      // No response at all — network/gateway down
      try {
        getUiState?.().setDegradationTier('maintenance')
      } catch {
        // Ignore store errors
      }
      return Promise.reject(new Error('Network error. Please check your connection.'))
    }
    return Promise.reject(error)
  },
)

export default apiClient
