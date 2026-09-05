import { Link, Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { SessionLoading } from './SessionLoading'
import { useAuth } from './AuthContext'
import { landingLabelFor, landingPathFor } from './roleLanding'
import type { UserRole } from '../../api'
import { cn } from '../utils/cn'

interface ProtectedRouteProps {
  children: ReactNode
  allowedRoles?: UserRole[]
  /** Nombre del módulo, para nombrarlo en la pantalla de sin acceso. */
  moduleName?: string
  /**
   * La acción en infinitivo ("registrar un servicio"), para las rutas cuyo permiso
   * es más angosto que el de su módulo. Sin esto, a un rol que tiene el módulo pero
   * no esa pantalla se le decía que no tenía acceso al módulo entero, y se le
   * ofrecía volver justamente ahí. En infinitivo y sin artículo porque el título lo
   * completa sin preposición: anteponerle una obligaría a elegir entre "a el" y "al"
   * según el género de lo que venga.
   */
  actionName?: string
}

/**
 * Vista inline para rol sin permiso. NO redirige automáticamente: como el
 * landing de cada rol también está protegido, un redirect produciría un loop.
 * El link ofrece ir al módulo donde ese rol SÍ trabaja (su landing): almacén
 * para sus dos roles, operaciones para el despachador.
 */
const exitLinkClasses =
  'mt-6 inline-block rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-on-solid hover:bg-accent-hover transition-colors'

function AccessDenied({
  role,
  moduleName,
  actionName,
}: {
  role: UserRole | undefined
  moduleName?: string
  actionName?: string
}) {
  const landing = landingPathFor(role)
  const label = `Ir a ${landingLabelFor(role)}`

  // <div> (no <main>): este componente se monta DENTRO del <main> de AppLayout
  // cuando la ruta protegida es hija del layout — un <main> anidado sería
  // HTML inválido y duplicaría el landmark para screen readers.
  return (
    <div className="flex items-center justify-center px-4 py-24">
      <div className="bg-surface rounded-2xl ring-1 ring-border p-8 text-center max-w-md">
        <h1 className="text-xl font-semibold text-fg">
          {actionName
            ? `No puedes ${actionName}`
            : moduleName
              ? `Sin acceso a ${moduleName}`
              : 'Sin acceso a este módulo'}
        </h1>
        <p className="mt-2 text-sm text-fg-muted">
          {actionName
            ? 'Tu rol no tiene permisos para esta acción.'
            : 'Tu rol no tiene permisos para este módulo.'}
        </p>
        {/* Todos los landings viven en esta SPA: navega el router, sin
            recargar la app entera para cambiar de módulo. */}
        <Link to={landing} className={cn(exitLinkClasses, 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface')}>
          {label}
        </Link>
      </div>
    </div>
  )
}

export function ProtectedRoute({
  children,
  allowedRoles,
  moduleName,
  actionName,
}: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <SessionLoading />
  }

  if (!isAuthenticated || !user) {
    return <Navigate to="/cotizaciones/login" replace state={{ from: location.pathname }} />
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <AccessDenied role={user.role} moduleName={moduleName} actionName={actionName} />
  }

  return <>{children}</>
}
