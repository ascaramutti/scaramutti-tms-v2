import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { client } from '../../api/client.gen'
import { refreshToken as refreshTokenRequest } from '../../api'
import { tokenStorage } from '../auth/tokenStorage'

type RetriableRequestConfig = InternalAxiosRequestConfig & { _retry?: boolean }

// Dedupe de refresh: si N requests fallan con 401 al mismo tiempo,
// solo UN POST /auth/refresh sale a la red. Las llamadas concurrentes a
// `tryRefresh` durante la ventana del request reciben la misma promesa.
//
// Limitacion conocida (multi-tab): el `currentRefreshToken` se captura una
// vez al iniciar el refresh. Si otra pestaña rota el token a mitad de
// ejecucion, las llamadas concurrentes pueden recibir un resultado basado
// en un token viejo. En single-tab esto no se da. Si en el futuro se
// agrega sincronizacion cross-tab, este punto requiere revision.
let refreshInFlight: Promise<string | null> | null = null

async function tryRefresh(): Promise<string | null> {
  const currentRefreshToken = tokenStorage.getRefreshToken()
  if (!currentRefreshToken) return null

  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const { data } = await refreshTokenRequest({
          body: { refreshToken: currentRefreshToken },
          throwOnError: true,
        })
        if (!data) return null
        tokenStorage.setTokens(data.token, data.refreshToken ?? null)
        return data.token
      } catch {
        tokenStorage.clear()
        return null
      } finally {
        refreshInFlight = null
      }
    })()
  }

  return refreshInFlight
}

export function configureHttpClient(onSessionExpired: () => void): void {
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL as string | undefined
  if (!apiBaseUrl) {
    throw new Error(
      'VITE_API_BASE_URL no esta definida. Definir en .env.<mode> antes de arrancar.',
    )
  }

  client.setConfig({
    baseURL: apiBaseUrl,
    auth: () => tokenStorage.getAccessToken() ?? undefined,
  })

  client.instance.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const originalRequest = error.config as RetriableRequestConfig | undefined
      const status = error.response?.status

      // Endpoints de auth no deben disparar refresh:
      // - /auth/refresh fallando = loop infinito
      // - /auth/login/change-password 401 = credenciales mal, no token expirado.
      //   Si refresheamos aca, podriamos sobreescribir tokens validos con los de
      //   otra sesion vieja (refresh token huerfano en localStorage).
      const url = originalRequest?.url ?? ''
      const isAuthEndpoint =
        url.endsWith('/auth/refresh') ||
        url.endsWith('/auth/login') ||
        url.endsWith('/auth/change-password')

      if (status === 401 && originalRequest && !originalRequest._retry && !isAuthEndpoint) {
        originalRequest._retry = true
        const newToken = await tryRefresh()
        if (newToken) {
          // Actualizar el header Authorization del request original.
          // Sin esto, axios reenvia el header viejo (capturado al construir el
          // request inicial) y el backend vuelve a rechazar con 401.
          // axios garantiza que `headers` es no-null (InternalAxiosRequestConfig).
          originalRequest.headers.Authorization = `Bearer ${newToken}`
          return client.instance.request(originalRequest)
        }
        onSessionExpired()
      }

      return Promise.reject(error)
    },
  )
}
