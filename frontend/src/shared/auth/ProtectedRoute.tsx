import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'
import type { UserRole } from '../../api'

interface ProtectedRouteProps {
  children: ReactNode
  allowedRoles?: UserRole[]
}

/**
 * Vista inline para rol sin permiso. NO redirige: como la lista vive en
 * /cotizaciones (protegida por rol), un redirect produciría un loop para
 * roles sin acceso al módulo (ej. dispatcher). El link lleva a v1 (raíz del
 * dominio, fuera de esta SPA), que es donde esos roles trabajan.
 */
function AccessDenied() {
  // <div> (no <main>): este componente se monta DENTRO del <main> de AppLayout
  // cuando la ruta protegida es hija del layout — un <main> anidado sería
  // HTML inválido y duplicaría el landmark para screen readers.
  return (
    <div className="flex items-center justify-center px-4 py-24">
      <div className="bg-white rounded-2xl ring-1 ring-slate-200 p-8 text-center max-w-md">
        <h1 className="text-xl font-semibold text-slate-900">Sin acceso a Cotizaciones</h1>
        <p className="mt-2 text-sm text-slate-500">
          Tu rol no tiene permisos para este módulo.
        </p>
        <a
          href="/"
          className="mt-6 inline-block rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          Ir a Servicios
        </a>
      </div>
    </div>
  )
}

export function ProtectedRoute({ children, allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <div role="status">Cargando sesión…</div>
  }

  if (!isAuthenticated || !user) {
    return <Navigate to="/cotizaciones/login" replace state={{ from: location.pathname }} />
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <AccessDenied />
  }

  return <>{children}</>
}
