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
 * Almacén: los cinco roles ven todas las pantallas del módulo y pueden registrar,
 * editar y anular entradas y retiros. `sales` y `dispatcher` quedan afuera.
 */
export const WAREHOUSE_ROLES: UserRole[] = [
  'admin',
  'general_manager',
  'operations_manager',
  'finance_manager',
  'warehouse_keeper',
]

/**
 * Única excepción dentro del módulo: registrar el corte inicial. Fija la línea
 * base del kardex de un producto, es inmutable y no tiene anulación, así que un
 * error solo se corrige en base de datos. Consultar las aperturas sigue abierto a
 * `WAREHOUSE_ROLES` (y el kardex del producto ya muestra su apertura).
 *
 * Espejo del `x-required-roles: [admin]` del POST; la autoridad es el 403 del
 * backend, esta lista solo decide qué esconde la UI.
 */
export const OPENING_BALANCE_REGISTER_ROLES: UserRole[] = ['admin']
