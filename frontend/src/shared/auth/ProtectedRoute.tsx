import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'
import type { UserRole } from '../../api'

interface ProtectedRouteProps {
  children: ReactNode
  allowedRoles?: UserRole[]
}

export function ProtectedRoute({ children, allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <div role="status">Cargando sesión…</div>
  }

  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}
