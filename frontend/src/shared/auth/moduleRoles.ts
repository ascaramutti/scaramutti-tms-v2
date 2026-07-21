import type { UserRole } from '../../api'

/**
 * Roles con acceso a cada módulo, espejo de `x-required-roles` del contrato
 * OpenAPI. La autoridad es el backend (403 COM-003): estas listas solo deciden
 * qué esconde la UI (item de menú) y cuándo muestra la pantalla de sin acceso.
 *
 * Viven acá y no en cada archivo para que el router y el sidebar no se
 * desincronicen: un item de menú visible que lleva a una ruta denegada, o al
 * revés, es un bug que solo se ve corriendo la app con ese rol.
 */
export const QUOTATION_ROLES: UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
]

/**
 * Operaciones (servicios y viajes) vive en v1, otra app en la raíz del dominio,
 * que valida sus propias sesiones: acá la lista solo evita ofrecer un link a
 * quien no tiene cuenta allá. Los roles de almacén trabajan únicamente en su
 * módulo y quedan afuera.
 */
export const OPERATIONS_ROLES: UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
  'dispatcher',
]

/**
 * Almacén: los cinco roles tienen poder total dentro del módulo (ver, registrar,
 * editar y anular), así que una sola lista alcanza para todas sus pantallas.
 * `sales` y `dispatcher` quedan afuera.
 */
export const WAREHOUSE_ROLES: UserRole[] = [
  'admin',
  'general_manager',
  'operations_manager',
  'finance_manager',
  'warehouse_keeper',
]
