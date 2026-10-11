import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import type { QueryClient } from '@tanstack/react-query'
import { client } from '../../api/client.gen'
import { refreshToken as refreshTokenRequest } from '../../api'
import { tokenStorage } from '../auth/tokenStorage'
import { expireSession, sessionGeneration } from '../auth/session'

type RetriableRequestConfig = InternalAxiosRequestConfig & {
  _retry?: boolean
  _sessionGeneration?: number
}

// Dedupe de refresh: si N requests fallan con 401 al mismo tiempo,
// solo UN POST /auth/refresh sale a la red. Las llamadas concurrentes a
// `tryRefresh` durante la ventana del request reciben la misma promesa,
// pero solo dentro de la misma generación de sesión: la de otra vuelve con
// null aunque salga bien, y esperarla dejaba afuera al usuario nuevo.
//
// Limitacion conocida (multi-tab): el `currentRefreshToken` se captura una
// vez al iniciar el refresh. Si otra pestaña rota el token a mitad de
// ejecucion, las llamadas concurrentes pueden recibir un resultado basado
// en un token viejo. En single-tab esto no se da. Si en el futuro se
// agrega sincronizacion cross-tab, este punto requiere revision.
let refreshInFlight: { generation: number; result: Promise<string | null> } | null = null

async function renew(refreshToken: string, generation: number): Promise<string | null> {
  try {
    const { data } = await refreshTokenRequest({ body: { refreshToken }, throwOnError: true })
    // Si la sesión cambió mientras volvía, los tokens son de otro usuario.
    if (!data || sessionGeneration() !== generation) return null
    tokenStorage.setTokens(data.token, data.refreshToken ?? null)
    return data.token
  } catch {
    // Los tokens los borra la rutina de fin de sesión, que corre con el null.
    return null
  }
}

async function tryRefresh(): Promise<string | null> {
  const currentRefreshToken = tokenStorage.getRefreshToken()
  if (!currentRefreshToken) return null

  const generation = sessionGeneration()
  if (refreshInFlight?.generation !== generation) {
    const inFlight = { generation, result: renew(currentRefreshToken, generation) }
    refreshInFlight = inFlight
    // Solo libera su propio lugar: si ya hay una renovación de la sesión nueva, es de ella.
    void inFlight.result.finally(() => {
      if (refreshInFlight === inFlight) refreshInFlight = null
    })
    return inFlight.result
  }
  return refreshInFlight.result
}

// Ninguna de las dos se descarta: el ingreso abre una sesión, y la renovación la decide `renew`
// con la generación, así su promesa siempre termina y no queda colgada en refreshInFlight. Desde
// que el dedupe es por generación, descartarla no traba a nadie: la diferencia no se ve en pantalla.
function isLoginOrRefresh(url: string | undefined): boolean {
  return !!url && (url.endsWith('/auth/login') || url.endsWith('/auth/refresh'))
}

function fromEndedSession(config: RetriableRequestConfig | undefined): boolean {
  return config?._sessionGeneration !== undefined && config._sessionGeneration !== sessionGeneration()
}

// La respuesta que vuelve cuando su sesión ya terminó no se entrega, ni como éxito ni como error:
// quien la pidió ya no está, y las dos escribirían en la sesión siguiente (la caché, un aviso, una
// navegación). La promesa queda pendiente sin que nadie la retenga, y se recolecta.
function neverDelivered(): Promise<never> {
  return new Promise<never>(() => {})
}

/**
 * El cableado del arranque: la sesión expirada corre la rutina de fin de sesión. Vive acá y no en
 * main.tsx para que los tests monten la aplicación con el mismo cableado que producción.
 */
export function configureSessionHttpClient(queryClient: QueryClient): void {
  configureHttpClient(() => expireSession(queryClient))
}

/** `onSessionExpired` tiene que borrar los tokens: la renovación fallida ya no los borra sola. */
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

  client.instance.interceptors.request.use((config: RetriableRequestConfig) => {
    if (!isLoginOrRefresh(config.url)) config._sessionGeneration = sessionGeneration()
    return config
  })

  client.instance.interceptors.response.use(
    (response) => (fromEndedSession(response.config) ? neverDelivered() : response),
    async (error: AxiosError) => {
      const originalRequest = error.config as RetriableRequestConfig | undefined
      if (fromEndedSession(originalRequest)) return neverDelivered()
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
        // La sesión terminó durante la renovación: ni se reintenta ni se da por expirada otra vez.
        if (fromEndedSession(originalRequest)) return neverDelivered()
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
