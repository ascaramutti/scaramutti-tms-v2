import { Link, useLocation } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'
import { cn } from '../utils/cn'
import { matchesPathPrefix } from './pathMatching'

interface SidebarNavItemProps {
  icon: LucideIcon
  label: string
  /** Si se pasa, el item es navegable. Si no, queda disabled. */
  to?: string
  /**
   * Matcher custom de "activo". El default es prefix-matching, que marca falsos
   * positivos cuando rutas de otra sección anidan bajo el mismo prefijo (ej.
   * /cotizaciones/cuenta/* y /cotizaciones/almacen/* no son Cotizaciones).
   */
  activeWhen?: (pathname: string) => boolean
}

const baseClasses =
  'group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors'

export function SidebarNavItem({ icon: Icon, label, to, activeWhen }: SidebarNavItemProps) {
  const location = useLocation()

  if (!to) {
    return (
      <li className="list-none">
        <span
          className={cn(baseClasses, 'cursor-not-allowed text-fg-subtle')}
          title="Próximamente"
          aria-disabled="true"
        >
          <Icon className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
          {label}
          <span className="sr-only"> (próximamente)</span>
        </span>
      </li>
    )
  }

  // El estado activo se calcula acá (y no con NavLink) para que el resaltado y
  // el `aria-current` salgan del MISMO criterio: NavLink impone su prefijo al
  // `aria-current`, y con un `activeWhen` que lo contradice el lector de
  // pantalla anunciaría como página actual un módulo en el que no estás.
  const isActive = activeWhen
    ? activeWhen(location.pathname)
    : matchesPathPrefix(location.pathname, to)

  return (
    <li className="list-none">
      <Link
        to={to}
        aria-current={isActive ? 'page' : undefined}
        className={cn(
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus',
          baseClasses,
          isActive
            ? 'bg-accent-soft text-accent-hover'
            : 'text-fg-body hover:bg-surface-muted hover:text-fg',
        )}
      >
        <Icon className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
        {label}
      </Link>
    </li>
  )
}
