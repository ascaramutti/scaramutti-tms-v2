import { FileText, Home, Truck, Users, type LucideIcon } from 'lucide-react'
import { SidebarNavItem } from './SidebarNavItem'
import { SidebarSection } from './SidebarSection'
import { SidebarFooter } from './SidebarFooter'
import { useAuth } from '../auth/AuthContext'
import type { UserRole } from '../../api'

interface MenuItem {
  icon: LucideIcon
  label: string
  /** Si se pasa, el item es navegable. Si no, disabled. */
  to?: string
  /** Si se pasa, solo los roles listados ven el item. Sin restricción → visible para todos. */
  allowedRoles?: UserRole[]
}

interface MenuGroup {
  /** Si tiene label se renderiza con `<SidebarSection>`; si no, como item suelto. */
  label?: string
  items: MenuItem[]
}

// Matriz de permisos del menú alineada con `x-required-roles` del contrato OpenAPI.
// Cuando se agregue un módulo nuevo, sumar el item acá con sus roles permitidos.
const MENU: MenuGroup[] = [
  {
    items: [{ icon: Home, label: 'Inicio', to: '/' }],
  },
  {
    label: 'Comercial',
    items: [
      {
        icon: FileText,
        label: 'Cotizaciones',
        allowedRoles: ['admin', 'sales', 'general_manager', 'operations_manager'],
      },
      {
        icon: Users,
        label: 'Clientes',
        allowedRoles: ['admin', 'sales', 'general_manager', 'operations_manager'],
      },
    ],
  },
]

function isVisibleFor(item: MenuItem, userRole: UserRole | undefined): boolean {
  if (!item.allowedRoles) return true
  if (!userRole) return false
  return item.allowedRoles.includes(userRole)
}

/**
 * Sidebar principal de la app.
 *
 * Semántica:
 * - El wrapper externo es `<aside>` (contenido lateral complementario).
 * - `<nav aria-label="Principal">` solo envuelve los menúes navegables.
 * - `SidebarFooter` (sesión + logout) vive afuera del `<nav>` porque logout no es navegación.
 *
 * Filtrado por rol:
 * - Items con `allowedRoles` solo se muestran al usuario si su rol está en la lista.
 * - Si todos los items de una sección quedan filtrados, la sección entera se oculta
 *   (no muestra `<h2>` huérfano).
 */
export function Sidebar() {
  const { user } = useAuth()
  const userRole = user?.role

  const visibleGroups = MENU.map((group) => ({
    label: group.label,
    items: group.items.filter((item) => isVisibleFor(item, userRole)),
  })).filter((group) => group.items.length > 0)

  return (
    <aside className="w-64 bg-white border-r border-slate-200 flex flex-col p-4 sticky top-0 h-screen overflow-y-auto">
      {/* Header / branding */}
      <div className="flex items-center gap-2.5 mb-6 px-1">
        <div className="bg-blue-600 p-1.5 rounded-lg flex-shrink-0">
          <Truck className="w-5 h-5 text-white" aria-hidden="true" />
        </div>
        <div>
          <p className="font-semibold text-slate-900 leading-tight">Scaramutti</p>
          <p className="text-xs text-slate-500 leading-tight">TMS · Gestión</p>
        </div>
      </div>

      {/* Navegación principal */}
      <nav aria-label="Principal" className="flex flex-col gap-5">
        {visibleGroups.map((group, idx) =>
          group.label ? (
            <SidebarSection key={group.label} label={group.label}>
              {group.items.map((item) => (
                <SidebarNavItem
                  key={item.label}
                  icon={item.icon}
                  label={item.label}
                  to={item.to}
                />
              ))}
            </SidebarSection>
          ) : (
            <ul key={`group-${idx}`} className="flex flex-col gap-0.5 list-none p-0 m-0">
              {group.items.map((item) => (
                <SidebarNavItem
                  key={item.label}
                  icon={item.icon}
                  label={item.label}
                  to={item.to}
                />
              ))}
            </ul>
          ),
        )}
      </nav>

      {/* Footer fuera del <nav> (logout no es navegación) */}
      <div className="mt-auto">
        <SidebarFooter />
      </div>
    </aside>
  )
}
