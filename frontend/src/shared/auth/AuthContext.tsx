import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react'
import { hashKey, useQuery, useQueryClient, type QueryFilters } from '@tanstack/react-query'
import { toast } from 'sonner'
import { getCurrentUser } from '../../api'
import type { SessionUser } from '../../api'
import { tokenStorage } from './tokenStorage'
import { currentUserQueryKey } from './queryKeys'
import { beginSession, endSession } from './session'

interface AuthContextValue {
  user: SessionUser | null
  isLoading: boolean
  isAuthenticated: boolean
  setSession(accessToken: string, refreshToken: string | null, user: SessionUser): void
  replaceSession(accessToken: string, refreshToken: string | null, user: SessionUser): void
  clearSession(): void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const hasToken = !!tokenStorage.getAccessToken()

  const { data, isLoading: queryLoading } = useQuery({
    queryKey: currentUserQueryKey,
    queryFn: async (): Promise<SessionUser | null> => {
      const { data: userResponse } = await getCurrentUser({ throwOnError: true })
      return userResponse ?? null
    },
    enabled: hasToken,
    retry: false,
    staleTime: Infinity,
  })

  const user = data ?? null
  const isAuthenticated = !!user
  const isLoading = hasToken && queryLoading

  const setSession = useCallback(
    (accessToken: string, refreshToken: string | null, sessionUser: SessionUser) => {
      beginSession()
      // La carga del usuario con los tokens de antes ya no se entrega: sin cancelarla, la query
      // quedaba cargando para siempre (medido), aunque la pantalla ya mostrara al que entró.
      void queryClient.cancelQueries({ queryKey: currentUserQueryKey })
      tokenStorage.setTokens(accessToken, refreshToken)
      queryClient.setQueryData<SessionUser | null>(currentUserQueryKey, sessionUser)
    },
    [queryClient],
  )

  // La sesión nueva del cambio de contraseña: la misma persona sigue adentro, así que no corre la
  // rutina de fin de sesión ni abre otra generación. Lo que estaba en vuelo es suyo y se entrega
  // (con otro cargo, las consultas se cortan y se vuelven a pedir: abajo).
  const replaceSession = useCallback(
    (accessToken: string, refreshToken: string | null, sessionUser: SessionUser) => {
      const previousRole = queryClient.getQueryData<SessionUser | null>(currentUserQueryKey)?.role
      // Como en el ingreso: la carga del usuario pedida con el token de antes no pisa al de la respuesta.
      void queryClient.cancelQueries({ queryKey: currentUserQueryKey })
      tokenStorage.setTokens(accessToken, refreshToken)
      queryClient.setQueryData<SessionUser | null>(currentUserQueryKey, sessionUser)
      // Con otro cargo, lo cargado puede tener la forma del anterior (el servidor responde según el
      // cargo): se marca vencido, sin borrarlo, y lo que está en pantalla se vuelve a pedir. Antes se
      // corta lo que está en vuelo: invalidar no corta una primera carga, que volvería como fresca.
      if (previousRole && previousRole !== sessionUser.role) {
        const currentUserHash = hashKey(currentUserQueryKey)
        const others: QueryFilters = { predicate: (query) => query.queryHash !== currentUserHash }
        void queryClient.cancelQueries(others).then(() => queryClient.invalidateQueries(others))
      }
    },
    [queryClient],
  )

  // Salir no recarga la página: sin la rutina, el que entra después en esta pestaña arranca con
  // la memoria del anterior. La comparte con la sesión expirada, que en el ingreso tampoco recarga.
  // Los avisos se cierran solo acá: al salir son del que sale; cuando la sesión expira en el
  // ingreso, son de quien está entrando (medido: se le cerraba el de la clave equivocada).
  const clearSession = useCallback(() => {
    endSession(queryClient)
    toast.dismiss()
  }, [queryClient])

  const value = useMemo<AuthContextValue>(
    () => ({ user, isLoading, isAuthenticated, setSession, replaceSession, clearSession }),
    [user, isLoading, isAuthenticated, setSession, replaceSession, clearSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth debe usarse dentro de <AuthProvider>')
  return ctx
}
