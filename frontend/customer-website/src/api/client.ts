import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'

let cachedAnonymousId: string | null = null

export function clearAnonymousIdCache(): void {
  cachedAnonymousId = null
}

function newGuestId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // getRandomValues also works on HTTP origins where randomUUID is unavailable.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function getAnonymousUserId(): string {
  if (cachedAnonymousId) return cachedAnonymousId
  try {
    cachedAnonymousId = localStorage.getItem('robomart-user-id')
  } catch {
    // Keep an in-memory guest cart when browser storage is unavailable.
  }
  if (!cachedAnonymousId) {
    cachedAnonymousId = newGuestId()
    try {
      localStorage.setItem('robomart-user-id', cachedAnonymousId)
    } catch {
      // The cached ID still persists for the lifetime of this page.
    }
  }
  return cachedAnonymousId
}

// Auth store accessor — set by main.ts after store initialization to avoid circular imports
let getAuthState:
  | (() => {
      accessToken: string | null
      userId: string | null
      refreshToken: () => Promise<boolean>
      logout: () => Promise<void>
    })
  | null = null

export function setAuthAccessor(
  accessor: () => {
    accessToken: string | null
    userId: string | null
    refreshToken: () => Promise<boolean>
    logout: () => Promise<void>
  },
): void {
  getAuthState = accessor
}

// UI store accessor — set by main.ts after store initialization
let getUiState:
  | (() => { setDegradationTier: (tier: 'normal' | 'partial' | 'maintenance') => void })
  | null = null

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
    // Guest cart id. The gateway strips client-supplied X-User-Id; only the JWT subject is relayed.
    config.headers['X-Cart-Id'] = getAnonymousUserId()
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
          await auth.logout().finally(() => {
            logoutInProgress = false
          })
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
