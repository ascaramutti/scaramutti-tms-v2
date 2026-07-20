import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'
import { landingLabelFor, landingPathFor } from './roleLanding'
import type { UserRole } from '../../api'

interface ProtectedRouteProps {
  children: ReactNode
  allowedRoles?: UserRole[]
  /** Nombre del módulo, para nombrarlo en la pantalla de sin acceso. */
  moduleName?: string
}

/**
 * Vista inline para rol sin permiso. NO redirige automáticamente: como el
 * landing de cada rol también está protegido, un redirect produciría un loop.
 * El link ofrece ir al módulo donde ese rol SÍ trabaja (su landing), que para
 * dispatcher es v1 (fuera de esta SPA) y para los roles de almacén es almacén.
 */
function AccessDenied({ role, moduleName }: { role: UserRole | undefined; moduleName?: string }) {
  // <div> (no <main>): este componente se monta DENTRO del <main> de AppLayout
  // cuando la ruta protegida es hija del layout — un <main> anidado sería
  // HTML inválido y duplicaría el landmark para screen readers.
  return (
    <div className="flex items-center justify-center px-4 py-24">
      <div className="bg-white rounded-2xl ring-1 ring-slate-200 p-8 text-center max-w-md">
        <h1 className="text-xl font-semibold text-slate-900">
          {moduleName ? `Sin acceso a ${moduleName}` : 'Sin acceso a este módulo'}
        </h1>
        <p className="mt-2 text-sm text-slate-500">
          Tu rol no tiene permisos para este módulo.
        </p>
        <a
          href={landingPathFor(role)}
          className="mt-6 inline-block rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          Ir a {landingLabelFor(role)}
        </a>
      </div>
    </div>
  )
}

export function ProtectedRoute({ children, allowedRoles, moduleName }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <div role="status">Cargando sesión…</div>
  }

  if (!isAuthenticated || !user) {
    return <Navigate to="/cotizaciones/login" replace state={{ from: location.pathname }} />
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <AccessDenied role={user.role} moduleName={moduleName} />
  }

  return <>{children}</>
}
