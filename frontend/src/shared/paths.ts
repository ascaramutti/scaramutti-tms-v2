/**
 * Las rutas de la SPA, en un solo lugar.
 *
 * Todo lo que en el código dice una URL de la aplicación sale de acá: el router,
 * la barra lateral, los `navigate`, los enlaces y la recarga al login del 401.
 * Fuera de este módulo no queda escrita ninguna, y una guarda de la suite lo
 * verifica en cada corrida.
 *
 * Cada una se declara con su valor entero y no compuesta desde una base común:
 * hasta la mudanza de 2026-09 la aplicación se servía bajo un prefijo heredado
 * de cuando convivía con la v1 detrás de un gateway, y ese prefijo era la base.
 * Ahora la SPA es la raíz del dominio, así que una base no agregaría nada y
 * componer desde una cadena vacía solo invita a la barra doble.
 */

/** Ingreso. Es también a donde vuelve la aplicación cuando la API da un 401. */
export const LOGIN_PATH = '/login'

/** Administrar cuenta. Hoy tiene una sola pantalla, pero es un subárbol. */
export const ACCOUNT_BASE = '/cuenta'

/** Cambio de contraseña, la única pantalla de "administrar cuenta". */
export const CHANGE_PASSWORD_PATH = `${ACCOUNT_BASE}/cambiar-contrasena`

/**
 * Módulo de cotizaciones. Es el único que conserva sus URL en la mudanza: el
 * prefijo viejo llevaba su nombre, así que para este módulo no cambió nada.
 */
export const QUOTATIONS_BASE = '/cotizaciones'

/** Módulo Almacén. */
export const WAREHOUSE_BASE = '/almacen'

/** Módulo Operaciones. */
export const OPERATIONS_BASE = '/operaciones'

/**
 * Los detalles con id se arman con una función solo donde varios sitios
 * construyen la misma URL; donde hay uno o dos, se compone con la base y se lee
 * igual de bien sin sumar vocabulario.
 */
export function quotationDetailPath(id: number | string): string {
  return `${QUOTATIONS_BASE}/${id}`
}

export function warehouseProductPath(id: number | string): string {
  return `${WAREHOUSE_BASE}/productos/${id}`
}
