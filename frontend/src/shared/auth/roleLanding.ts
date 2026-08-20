import type { UserRole } from '../../api'

/**
 * Pantalla de aterrizaje post-login según el rol (unificación v1+v2).
 *
 * Detrás del gateway las dos apps comparten el origin: `/` es v1 y
 * `/cotizaciones` es v2. Cada rol aterriza donde trabaja.
 *
 * `dispatcher` aterrizaba en v1 porque el control de viajes vivía allá; con el
 * módulo recodificado en v2 aterriza en Operaciones, dentro de esta SPA. v1
 * sigue en pie hasta el cutover de datos, pero deja de ser el destino de nadie:
 * ningún rol aterriza fuera de la SPA.
 */
export const COTIZACIONES_LANDING = '/cotizaciones'

/**
 * Raíz del dominio = v1. Ya no es landing de ningún rol, pero se conserva: es
 * la referencia de "afuera de esta SPA" que usa `isExternalLanding`, y v1 sigue
 * sirviendo el control de viajes hasta el cutover.
 */
export const V1_LANDING = '/'

/**
 * Módulo Almacén. Vive DENTRO de esta SPA: el prefijo `/cotizaciones` es el
 * `base` de Vite (la app entera se sirve ahí), no el módulo de cotizaciones.
 */
export const ALMACEN_LANDING = '/cotizaciones/almacen'

/** Módulo Operaciones. Cuelga del mismo prefijo, por la misma razón. */
export const OPERACIONES_LANDING = '/cotizaciones/operaciones'

// Los dos roles del módulo Almacén trabajan solo ahí, así que aterrizan en el
// módulo y no en cotizaciones (matriz de permisos del contrato de Almacén).
const ROLE_LANDING: Record<UserRole, string> = {
  admin: COTIZACIONES_LANDING,
  sales: COTIZACIONES_LANDING,
  general_manager: COTIZACIONES_LANDING,
  operations_manager: COTIZACIONES_LANDING,
  dispatcher: OPERACIONES_LANDING,
  finance_manager: ALMACEN_LANDING,
  warehouse_keeper: ALMACEN_LANDING,
}

export function landingPathFor(role: UserRole | undefined): string {
  if (!role) return COTIZACIONES_LANDING
  return ROLE_LANDING[role] ?? COTIZACIONES_LANDING
}

/** Nombre visible de cada landing, para los links que ofrecen "ir a…". */
const LANDING_LABEL: Record<string, string> = {
  [V1_LANDING]: 'Servicios',
  [COTIZACIONES_LANDING]: 'Cotizaciones',
  [ALMACEN_LANDING]: 'Almacén',
  [OPERACIONES_LANDING]: 'Operaciones',
}

export function landingLabelFor(role: UserRole | undefined): string {
  return LANDING_LABEL[landingPathFor(role)] ?? 'Servicios'
}

/**
 * Un landing fuera de /cotizaciones vive en otra SPA (v1): hay que navegar
 * con window.location (full page load), no con el router de React.
 * Match por segmento (no por prefijo crudo): un hipotético /cotizacionesX
 * NO es interno.
 */
export function isExternalLanding(path: string): boolean {
  return !(path === COTIZACIONES_LANDING || path.startsWith(`${COTIZACIONES_LANDING}/`))
}
