import { NavLink, useLocation } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'
import { cn } from '../utils/cn'

interface SidebarNavItemProps {
  icon: LucideIcon
  label: string
  /** Si se pasa, el item es navegable. Si no, queda disabled. */
  to?: string
  /**
   * Matcher custom de "activo". Por defecto NavLink usa prefix-matching, que
   * marca falsos positivos cuando rutas de otra sección anidan bajo el mismo
   * prefijo (ej. /cotizaciones/cuenta/* no es parte del módulo Cotizaciones).
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

  // `end` solo en la raíz `/` para que rutas hijas (ej. /clientes/123) no
  // apaguen el highlight del padre (/clientes).
  return (
    <li className="list-none">
      <NavLink
        to={to}
        end={to === '/'}
        className={({ isActive }) =>
          cn(
            baseClasses,
            (activeWhen ? activeWhen(location.pathname) : isActive)
              ? 'bg-blue-50 text-blue-700'
              : 'text-slate-700 hover:bg-slate-100 hover:text-slate-900',
          )
        }
      >
        <Icon className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
        {label}
      </NavLink>
    </li>
  )
}
