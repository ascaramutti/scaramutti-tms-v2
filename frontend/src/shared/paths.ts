/**
 * Las rutas de la SPA, en un solo lugar.
 *
 * Todo lo que en el código dice una URL de la aplicación sale de acá: el router,
 * la barra lateral, los `navigate`, los enlaces y la recarga al login del 401.
 * Fuera de este módulo no queda escrito el prefijo, y una guarda de la suite lo
 * verifica en cada corrida.
 */

/**
 * El prefijo bajo el que se sirve la aplicación entera: es el `base` de Vite, no
 * el nombre de un módulo. Viene de cuando la v2 era el módulo de cotizaciones y
 * convivía con la v1 detrás de un gateway que ruteaba por prefijo; almacén y
 * operaciones se sumaron después adentro y heredaron ese prefijo, que por eso
 * hoy dice "cotizaciones" delante de todo.
 *
 * La v1 y el gateway se retiraron, así que el prefijo dejó de tener sentido:
 * **el PR que sigue a esta centralización cambia este valor a `/`** y con él se
 * mueven login, cuenta, almacén y operaciones. Las URL del módulo de
 * cotizaciones no cambian, porque ese módulo sí se llama así.
 */
export const SPA_BASE = '/cotizaciones'

/** Ingreso. Es también a donde vuelve la aplicación cuando la API da un 401. */
export const LOGIN_PATH = `${SPA_BASE}/login`

/** Cambio de contraseña, la única pantalla de "administrar cuenta". */
export const CHANGE_PASSWORD_PATH = `${SPA_BASE}/cuenta/cambiar-contrasena`

/**
 * Módulo de cotizaciones. Hoy coincide con la base porque el prefijo lleva su
 * nombre; después de la mudanza deja de coincidir y pasa a ser un segmento más.
 */
export const QUOTATIONS_BASE = SPA_BASE

/** Módulo Almacén. */
export const WAREHOUSE_BASE = `${SPA_BASE}/almacen`

/** Módulo Operaciones. */
export const OPERATIONS_BASE = `${SPA_BASE}/operaciones`

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
