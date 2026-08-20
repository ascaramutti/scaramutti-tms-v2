import type { UserRole } from '../../api'

/**
 * Pantalla de aterrizaje post-login según el rol.
 *
 * Cada rol aterriza donde trabaja. Hasta que el control de viajes se recodificó
 * en v2, `dispatcher` salía a `/` (v1, otra SPA en la raíz del dominio) y por
 * eso el aterrizaje sabía navegar hacia afuera con una recarga completa. Ahora
 * **todos los landings viven dentro de esta SPA**, así que la navegación
 * externa se retiró: era una rama que ya no podía ejecutarse.
 *
 * Si algún día un rol volviera a trabajar fuera de v2, hay que reponer esa
 * navegación (está en el historial); no alcanza con apuntar el landing afuera,
 * porque el router no puede navegar a otra app.
 */
export const COTIZACIONES_LANDING = '/cotizaciones'

/**
 * Módulo Almacén. Vive DENTRO de esta SPA: el prefijo `/cotizaciones` es el
 * `base` de Vite (la app entera se sirve ahí), no el módulo de cotizaciones.
 */
export const ALMACEN_LANDING = '/cotizaciones/almacen'

/** Módulo Operaciones. Cuelga del mismo prefijo, por la misma razón. */
export const OPERACIONES_LANDING = '/cotizaciones/operaciones'

/**
 * Los tres destinos posibles. Tenerlos como unión (y no como `string` suelto)
 * hace que el compilador exija una etiqueta por cada uno: sumar un módulo sin
 * su nombre visible deja de ser un `undefined` en pantalla y pasa a no compilar.
 */
type Landing =
  | typeof COTIZACIONES_LANDING
  | typeof ALMACEN_LANDING
  | typeof OPERACIONES_LANDING

// Los roles del módulo Almacén trabajan solo ahí, y el despachador solo en
// operaciones: cada uno aterriza en su módulo y no en cotizaciones.
const ROLE_LANDING: Record<UserRole, Landing> = {
  admin: COTIZACIONES_LANDING,
  sales: COTIZACIONES_LANDING,
  general_manager: COTIZACIONES_LANDING,
  operations_manager: COTIZACIONES_LANDING,
  dispatcher: OPERACIONES_LANDING,
  finance_manager: ALMACEN_LANDING,
  warehouse_keeper: ALMACEN_LANDING,
}

/**
 * Los roles conocidos, derivados del mapa y no escritos a mano: un rol nuevo
 * entra solo en los recorridos que verifican que todos aterrizan adentro.
 */
export const ALL_ROLES = Object.keys(ROLE_LANDING) as UserRole[]

export function landingPathFor(role: UserRole | undefined): Landing {
  if (!role) return COTIZACIONES_LANDING
  return ROLE_LANDING[role] ?? COTIZACIONES_LANDING
}

/** Nombre visible de cada landing, para los links que ofrecen "ir a…". */
const LANDING_LABEL: Record<Landing, string> = {
  [COTIZACIONES_LANDING]: 'Cotizaciones',
  [ALMACEN_LANDING]: 'Almacén',
  [OPERACIONES_LANDING]: 'Operaciones',
}

export function landingLabelFor(role: UserRole | undefined): string {
  return LANDING_LABEL[landingPathFor(role)]
}
