import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getCurrentUser } from '../../api'
import type { UserResponse } from '../../api'
import { tokenStorage } from './tokenStorage'
import { currentUserQueryKey } from './queryKeys'

interface AuthContextValue {
  user: UserResponse | null
  isLoading: boolean
  isAuthenticated: boolean
  setSession(accessToken: string, refreshToken: string | null, user: UserResponse): void
  clearSession(): void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const hasToken = !!tokenStorage.getAccessToken()

  const { data, isLoading: queryLoading } = useQuery({
    queryKey: currentUserQueryKey,
    queryFn: async (): Promise<UserResponse | null> => {
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
    (accessToken: string, refreshToken: string | null, sessionUser: UserResponse) => {
      tokenStorage.setTokens(accessToken, refreshToken)
      queryClient.setQueryData<UserResponse | null>(currentUserQueryKey, sessionUser)
    },
    [queryClient],
  )

  const clearSession = useCallback(() => {
    tokenStorage.clear()
    // setQueryData(null) en vez de removeQueries: garantiza que el useQuery
    // dispare un re-render con data=null, incluso cuando la query queda
    // disabled tras limpiar el token. removeQueries puede no notificar a
    // suscriptores cuando enabled pasa a false.
    queryClient.setQueryData<UserResponse | null>(currentUserQueryKey, null)
  }, [queryClient])

  const value = useMemo<AuthContextValue>(
    () => ({ user, isLoading, isAuthenticated, setSession, clearSession }),
    [user, isLoading, isAuthenticated, setSession, clearSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth debe usarse dentro de <AuthProvider>')
  return ctx
}
