import type { UserRole } from '../../../api'
import { SERVICE_CREATE_ROLES, SERVICE_OPERATE_ROLES } from '../../../shared/auth/moduleRoles'

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

/**
 * `true` si el rol puede operar el viaje: asignar recursos, sumar y quitar refuerzos
 * y moverlo de estado.
 *
 * Decide si la pantalla ofrece los botones. La garantía es del servidor; acá se evita
 * mostrarle a ventas un camino que termina en 403. Ventas igual ve las fichas con los
 * recursos: se le saca la acción, no el dato.
 */
export function canOperateService(role: UserRole | undefined): boolean {
  return role !== undefined && SERVICE_OPERATE_ROLES.includes(role)
}

/**
 * Roles que pueden dar de alta un cliente o un tipo de carga sin salir del
 * formulario.
 *
 * Otra lista con los mismos cuatro nombres, y por otro motivo: son los roles que el
 * servidor admite en `POST /clients` y `POST /cargo-types`, endpoints de otros
 * módulos que esta pantalla apenas consume. Si mañana el catálogo se abre a alguien
 * más, se toca acá y no donde se decide quién registra viajes.
 */
const CATALOG_CREATE_ROLES: readonly UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
]

/**
 * `true` si el rol puede registrar un servicio.
 *
 * Decide si la pantalla ofrece el botón. La garantía es del servidor; acá se evita
 * mostrarle al despacho un camino que termina en un 403.
 */
export function canCreateService(role: UserRole | undefined): boolean {
  return role !== undefined && SERVICE_CREATE_ROLES.includes(role)
}

/**
 * `true` si el rol puede crear un cliente o un tipo de carga al vuelo.
 *
 * Decide si los buscadores del formulario ofrecen el atajo de alta. Quien no lo
 * tiene igual puede elegir entre los que ya existen: se le saca el botón, no el
 * campo.
 */
export function canCreateCatalogEntry(role: UserRole | undefined): boolean {
  return role !== undefined && CATALOG_CREATE_ROLES.includes(role)
}
