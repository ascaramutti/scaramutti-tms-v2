import type { UserRole } from '../../api'

/**
 * Pantalla de aterrizaje post-login según el rol (unificación v1+v2).
 *
 * Detrás del gateway las dos apps comparten el origin: `/` es v1 (servicios/
 * viajes, FUERA de esta SPA) y `/cotizaciones` es v2. Cada rol aterriza donde
 * trabaja. Confirmado con el usuario (2026-06-12): dispatcher → v1; el resto
 * (incl. operations_manager) → cotizaciones.
 */
export const COTIZACIONES_LANDING = '/cotizaciones'

/** Raíz del dominio = v1. Navegación EXTERNA a esta SPA (window.location). */
export const V1_LANDING = '/'

const ROLE_LANDING: Record<UserRole, string> = {
  admin: COTIZACIONES_LANDING,
  sales: COTIZACIONES_LANDING,
  general_manager: COTIZACIONES_LANDING,
  operations_manager: COTIZACIONES_LANDING,
  dispatcher: V1_LANDING,
}

export function landingPathFor(role: UserRole | undefined): string {
  if (!role) return COTIZACIONES_LANDING
  return ROLE_LANDING[role] ?? COTIZACIONES_LANDING
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
