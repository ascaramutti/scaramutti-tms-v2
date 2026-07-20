import { Link, useLocation } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'
import { cn } from '../utils/cn'

interface SidebarNavItemProps {
  icon: LucideIcon
  label: string
  /** Si se pasa, el item es navegable. Si no, queda disabled. */
  to?: string
  /**
   * Link EXTERNO a esta SPA (ej. v1 en la raíz del dominio): ancla plana con
   * full page load, sin estado activo. Tiene prioridad sobre `to`.
   */
  href?: string
  /**
   * Matcher custom de "activo". El default es prefix-matching, que marca falsos
   * positivos cuando rutas de otra sección anidan bajo el mismo prefijo (ej.
   * /cotizaciones/cuenta/* y /cotizaciones/almacen/* no son Cotizaciones).
   */
  activeWhen?: (pathname: string) => boolean
}

const baseClasses =
  'group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors'

/**
 * Prefix-matching por segmento: /clientes/123 marca activo a /clientes, pero
 * /clientesX no. La raíz `/` matchea solo exacta (si no, marcaría todo).
 */
function matchesPathPrefix(pathname: string, to: string): boolean {
  if (to === '/') return pathname === '/'
  return pathname === to || pathname.startsWith(`${to}/`)
}

export function SidebarNavItem({ icon: Icon, label, to, href, activeWhen }: SidebarNavItemProps) {
  const location = useLocation()

  if (href) {
    return (
      <li className="list-none">
        <a
          href={href}
          className={cn(baseClasses, 'text-slate-700 hover:bg-slate-100 hover:text-slate-900')}
        >
          <Icon className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
          {label}
        </a>
      </li>
    )
  }

  if (!to) {
    return (
      <li className="list-none">
        <span
          className={cn(baseClasses, 'cursor-not-allowed text-slate-400')}
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
          baseClasses,
          isActive
            ? 'bg-blue-50 text-blue-700'
            : 'text-slate-700 hover:bg-slate-100 hover:text-slate-900',
        )}
      >
        <Icon className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
        {label}
      </Link>
    </li>
  )
}
