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
 * Operaciones (control de viajes). Vive DENTRO de esta SPA desde que el módulo
 * se recodificó en v2: la lista decide qué esconde la UI (item de menú y
 * pantalla de sin acceso), y la autoridad sigue siendo el backend.
 *
 * `dispatcher` entra acá y en ningún otro módulo: es su único lugar de trabajo.
 * Los roles de almacén quedan afuera.
 */
export const OPERATIONS_ROLES: UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
  'dispatcher',
]

/**
 * Excepción intramódulo: el reporte semanal de operaciones deja afuera al
 * despachador. El reporte muestra precios por viaje, y el backend omite
 * `price` y `currencyCode` para el despacho en todo el módulo. (Ojo: eso cubre
 * los importes estructurados, no un precio que alguien escriba a mano en un
 * texto libre; ese riesgo está aceptado en el contrato.)
 *
 * Espejo del `x-required-roles` de `GET /services/report`; la autoridad es el
 * 403 del backend, esta lista solo decide qué esconde la UI.
 */
export const SERVICES_REPORT_ROLES: UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
]

/**
 * Segunda excepción intramódulo: registrar un viaje deja afuera al despachador.
 * El cuerpo del alta obliga a mandar el precio, y quien no puede VER los importes
 * tampoco puede escribirlos a ciegas, así que el backend le contesta 403 aunque
 * sume otro rol. Lista propia y no la del reporte: hoy coinciden, pero son dos
 * reglas distintas y compartirlas haría que mover una mueva la otra.
 *
 * Espejo del `x-required-roles` de `POST /services`; la autoridad es el 403 del
 * backend, esta lista decide la ruta y el botón del listado.
 */
export const SERVICE_CREATE_ROLES: UserRole[] = [
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
]

/**
 * Tercera excepción intramódulo, y la única que deja afuera a ventas en vez de al
 * despachador: OPERAR el viaje. Ventas registra y edita el servicio; moverlo por su
 * ciclo de vida es del despacho y de la gerencia (D-OPS-15).
 *
 * A diferencia de las dos listas de arriba, ésta SÍ se comparte entre endpoints, y el
 * criterio es que detrás hay UNA regla y no varias que coinciden: asignar recursos,
 * sumar y quitar refuerzos y transicionar el estado declaran la misma lista, y el día
 * que la regla se mueva se mueve para los cuatro a la vez. (Verificable: son las
 * únicas cuatro rutas de `ServiceResource.java` con este `@RolesAllowed`.) Las otras
 * dos listas del módulo llegan al mismo reparto por caminos distintos, y por eso no
 * se comparten.
 *
 * Ojo con lo que esta lista NO decide: dentro de la transición de estado el reparto no
 * es plano (el despacho no cancela ni elimina, y reabrir es solo de admin y gerencia
 * general). Acá se decide quién puede LLAMAR; qué puede pedir cada uno es otra capa.
 *
 * Espejo del `x-required-roles`; la autoridad es el 403 del backend.
 */
export const SERVICE_OPERATE_ROLES: UserRole[] = [
  'admin',
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
