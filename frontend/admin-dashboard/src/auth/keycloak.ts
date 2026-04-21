import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180'
const realm = import.meta.env.VITE_KEYCLOAK_REALM || 'robomart'
const clientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'robo-mart-admin'
const appOrigin = window.location.origin

export const userManager = new UserManager({
  authority: `${keycloakUrl}/realms/${realm}`,
  client_id: clientId,
  redirect_uri: `${appOrigin}/admin/auth/callback`,
  post_logout_redirect_uri: `${appOrigin}/admin/unauthorized`,
  silent_redirect_uri: `${appOrigin}/silent-renew.html`,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: sessionStorage }),
})
