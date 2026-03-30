import type { User, SigninRedirectArgs } from 'oidc-client-ts'
import { userManager } from './keycloak'

export interface AuthEventCallbacks {
  onUserLoaded: (user: User) => void
  onUserUnloaded: () => void
  onSilentRenewError: (error: Error) => void
}

export function subscribeToAuthEvents(callbacks: AuthEventCallbacks): () => void {
  userManager.events.addUserLoaded(callbacks.onUserLoaded)
  userManager.events.addUserUnloaded(callbacks.onUserUnloaded)
  userManager.events.addSilentRenewError(callbacks.onSilentRenewError)

  return () => {
    userManager.events.removeUserLoaded(callbacks.onUserLoaded)
    userManager.events.removeUserUnloaded(callbacks.onUserUnloaded)
    userManager.events.removeSilentRenewError(callbacks.onSilentRenewError)
  }
}

const REDIRECT_PATH_KEY = 'robomart-auth-redirect'

export function saveRedirectPath(path: string): void {
  sessionStorage.setItem(REDIRECT_PATH_KEY, path)
}

export function consumeRedirectPath(): string {
  const path = sessionStorage.getItem(REDIRECT_PATH_KEY) || '/'
  sessionStorage.removeItem(REDIRECT_PATH_KEY)
  // Prevent open redirect — only allow relative paths
  if (!path.startsWith('/') || path.startsWith('//')) return '/'
  return path
}

export async function login(extraParams?: Record<string, string>): Promise<void> {
  const args: SigninRedirectArgs = {}
  if (extraParams) {
    args.extraQueryParams = extraParams
  }
  await userManager.signinRedirect(args)
}

export async function loginCallback(): Promise<User> {
  return userManager.signinRedirectCallback()
}

export async function register(): Promise<void> {
  await userManager.signinRedirect({
    extraQueryParams: { kc_action: 'register' },
  })
}

export async function logout(): Promise<void> {
  await userManager.signoutRedirect()
}

export async function renewToken(): Promise<User | null> {
  return userManager.signinSilent()
}

export async function getUser(): Promise<User | null> {
  return userManager.getUser()
}

export function getAccessToken(user: User | null): string | null {
  if (!user || user.expired) return null
  return user.access_token
}
