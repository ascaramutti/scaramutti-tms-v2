import type { UserRole } from '../../../api'

/**
 * Roles que ven los importes de un servicio. Espeja la lista positiva del
 * servidor; el veto que este aplica encima no hace falta acá porque el usuario
 * llega con un solo rol.
 *
 * Se escribe en positivo a propósito, igual que el backend: con la regla al revés
 * ("todos menos el despacho"), un rol nuevo que entre a Operaciones heredaría el
 * permiso sin que nadie lo decida, y se encontraría una columna de guiones porque
 * el servidor sí le omite el importe.
 */
const SERVICE_PRICE_ROLES: readonly UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
]

/**
 * `true` si el rol puede ver los importes de un servicio.
 *
 * El despacho opera los viajes, no ve lo que se cobra por ellos. La garantía real
 * es del servidor, que a `dispatcher` le OMITE `price` y `currencyCode` de cada
 * fila (ausentes, no null): acá solo se decide si la columna se arma o no, para
 * no dejar una columna entera de guiones. Es una decisión de presentación, no un
 * control de acceso.
 */
export function canSeeServicePrices(role: UserRole | undefined): boolean {
  return role !== undefined && SERVICE_PRICE_ROLES.includes(role)
}
