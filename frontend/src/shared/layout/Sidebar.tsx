import {
  ArrowDownToLine,
  ArrowUpFromLine,
  Boxes,
  ClipboardList,
  FileBarChart2,
  FileText,
  KeyRound,
  Route,
  Truck,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { SidebarNavItem } from './SidebarNavItem'
import { matchesPathPrefix } from './pathMatching'
import { SidebarSection } from './SidebarSection'
import { SidebarFooter } from './SidebarFooter'
import { useAuth } from '../auth/AuthContext'
import {
  OPERATIONS_ROLES,
  QUOTATION_ROLES,
  SERVICES_REPORT_ROLES,
  WAREHOUSE_ROLES,
} from '../auth/moduleRoles'
import type { UserRole } from '../../api'

interface MenuItem {
  icon: LucideIcon
  label: string
  /** Si se pasa, el item es navegable. Si no, disabled. */
  to?: string
  /** Si se pasa, solo los roles listados ven el item. Sin restricción → visible para todos. */
  allowedRoles?: UserRole[]
  /** Matcher custom de "activo" (ver SidebarNavItem.activeWhen). */
  activeWhen?: (pathname: string) => boolean
}

interface MenuGroup {
  /** Encabezado de la sección. Obligatorio: un grupo sin título dejaría items
   *  sueltos sin contexto, y ningún módulo lo necesita. */
  label: string
  items: MenuItem[]
}

/**
 * Subárboles que cuelgan de /cotizaciones (el `base` de Vite, o sea la SPA
 * entera) pero NO son el módulo comercial. Sin esta lista el prefijo marcaría
 * activo el item de Cotizaciones mientras el usuario está en otro módulo.
 */
const NON_QUOTATION_SUBTREES = [
  '/cotizaciones/cuenta',
  '/cotizaciones/almacen',
  '/cotizaciones/operaciones',
]

const WAREHOUSE_BASE = '/cotizaciones/almacen'
const OPERATIONS_BASE = '/cotizaciones/operaciones'

// Matriz de permisos del menú alineada con `x-required-roles` del contrato OpenAPI.
// Cuando se agregue un módulo nuevo, sumar el item acá con sus roles permitidos.
const MENU: MenuGroup[] = [
  {
    label: 'Operaciones',
    items: [
      {
        icon: Route,
        label: 'Servicios',
        to: OPERATIONS_BASE,
        allowedRoles: OPERATIONS_ROLES,
        // El detalle y el alta de un viaje van a colgar de acá cuando lleguen
        // sus pantallas, y tienen que seguir marcando activo Servicios. El
        // prefijo pelado no sirve: marcaría activo el item también en Reportes.
        activeWhen: (pathname) =>
          pathname === OPERATIONS_BASE ||
          matchesPathPrefix(pathname, `${OPERATIONS_BASE}/servicios`),
      },
      {
        // Sin `to` hasta que exista su pantalla: el item queda deshabilitado en
        // vez de llevar a una ruta que no resuelve. Roles propios: el reporte
        // deja afuera al despachador, a diferencia del resto del módulo.
        icon: FileBarChart2,
        label: 'Reportes de operaciones',
        allowedRoles: SERVICES_REPORT_ROLES,
      },
    ],
  },
  {
    label: 'Almacén',
    items: [
      {
        icon: Boxes,
        label: 'Existencias',
        to: WAREHOUSE_BASE,
        allowedRoles: WAREHOUSE_ROLES,
        // El detalle de un producto se abre desde acá, así que sigue marcando
        // activo Existencias. El prefijo pelado no sirve: marcaría activo el
        // item también en entradas, retiros y reportes.
        activeWhen: (pathname) =>
          pathname === WAREHOUSE_BASE ||
          matchesPathPrefix(pathname, `${WAREHOUSE_BASE}/productos`),
      },
      {
        icon: ArrowDownToLine,
        label: 'Entradas',
        to: `${WAREHOUSE_BASE}/entradas`,
        allowedRoles: WAREHOUSE_ROLES,
        activeWhen: (pathname) =>
          matchesPathPrefix(pathname, `${WAREHOUSE_BASE}/entradas`),
      },
      {
        icon: ArrowUpFromLine,
        label: 'Retiros',
        to: `${WAREHOUSE_BASE}/retiros`,
        allowedRoles: WAREHOUSE_ROLES,
        activeWhen: (pathname) =>
          matchesPathPrefix(pathname, `${WAREHOUSE_BASE}/retiros`),
      },
      {
        icon: FileBarChart2,
        label: 'Reportes',
        to: `${WAREHOUSE_BASE}/reportes`,
        allowedRoles: WAREHOUSE_ROLES,
        activeWhen: (pathname) => matchesPathPrefix(pathname, `${WAREHOUSE_BASE}/reportes`),
      },
      {
        // Último del grupo: es una tarea de arranque del módulo, no del día a día.
        icon: ClipboardList,
        label: 'Corte inicial',
        to: `${WAREHOUSE_BASE}/corte-inicial`,
        allowedRoles: WAREHOUSE_ROLES,
        activeWhen: (pathname) =>
          matchesPathPrefix(pathname, `${WAREHOUSE_BASE}/corte-inicial`),
      },
    ],
  },
  {
    label: 'Comercial',
    items: [
      {
        icon: FileText,
        label: 'Cotizaciones',
        to: '/cotizaciones',
        allowedRoles: QUOTATION_ROLES,
        activeWhen: (pathname) =>
          matchesPathPrefix(pathname, '/cotizaciones') &&
          !NON_QUOTATION_SUBTREES.some((subtree) => matchesPathPrefix(pathname, subtree)),
      },
      {
        icon: Users,
        label: 'Clientes',
        allowedRoles: QUOTATION_ROLES,
      },
    ],
  },
  {
    label: 'Administrar cuenta',
    items: [
      // Sin allowedRoles → visible para todos. Cualquier usuario puede cambiar
      // su propia contraseña, independientemente del rol.
      { icon: KeyRound, label: 'Cambiar contraseña', to: '/cotizaciones/cuenta/cambiar-contrasena' },
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
        {visibleGroups.map((group) => (
          <SidebarSection key={group.label} label={group.label}>
            {group.items.map((item) => (
              <SidebarNavItem
                key={item.label}
                icon={item.icon}
                label={item.label}
                to={item.to}
                activeWhen={item.activeWhen}
              />
            ))}
          </SidebarSection>
        ))}
      </nav>

      {/* Footer fuera del <nav> (logout no es navegación) */}
      <div className="mt-auto">
        <SidebarFooter />
      </div>
    </aside>
  )
}
