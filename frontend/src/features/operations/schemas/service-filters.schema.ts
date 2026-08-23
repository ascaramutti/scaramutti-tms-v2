import type { ServiceStatus } from '../../../api'

/** Tope de la búsqueda libre, espejo del `maxLength` del contrato. */
export const SERVICE_SEARCH_MAX_LENGTH = 200

/** Mínimo de caracteres que el backend indexa de una palabra del término. */
export const SERVICE_SEARCH_MIN_LENGTH = 3

/**
 * `true` si al menos una palabra del término llega al mínimo que el backend indexa.
 *
 * No alcanza con medir el largo total, que es el gate del resto del sistema: acá el
 * backend tokeniza `q` por espacios, descarta las palabras de menos de 3 caracteres
 * y responde 400 si no queda ninguna. Con la regla del largo total, "de la" (5
 * caracteres, cero palabras útiles) saldría a buscar para volver con un 400 que la
 * pantalla podía haber evitado.
 *
 * Lo que NO se replica: el tope de 8 palabras y el rechazo de caracteres de control.
 * Esos los reporta el backend con su propio mensaje; duplicarlos acá sería tener la
 * misma regla escrita en dos lugares que se van a desincronizar.
 */
export function hasSearchableToken(q: string): boolean {
  return q.split(/\s+/).some((word) => word.length >= SERVICE_SEARCH_MIN_LENGTH)
}

/**
 * `true` si el rango de fechas es consultable: vacío, a medio llenar, o con el
 * desde anterior o igual al hasta. Comparar las cadenas `YYYY-MM-DD` alcanza y es
 * lo correcto: son date-only, y pasarlas por `new Date()` las interpretaría como
 * medianoche UTC, que en Lima es el día anterior.
 */
export function isValidDateRange(from?: string, to?: string): boolean {
  if (!from || !to) return true
  return from <= to
}

/**
 * Filtros del listado de servicios. No hay schema zod porque no hay nada que
 * parsear: no es un form, es el estado local de la barra, y cada control ya solo
 * puede producir valores válidos (el select ofrece el enum, los inputs de fecha
 * emiten `YYYY-MM-DD`). Un zod que nadie ejecuta solo aparenta validar.
 *
 * Los topes del contrato se aplican donde el usuario escribe: el `maxLength` del
 * input de búsqueda y el `min`/`max` de los de fecha.
 */
export interface ServiceFilters {
  q: string
  status?: ServiceStatus
  /** `tentativeDate` desde/hasta, inclusive, en formato `YYYY-MM-DD`. */
  dateFrom?: string
  dateTo?: string
}

/** Estado inicial: sin filtros. Los eliminados quedan fuera salvo que se pidan. */
export const EMPTY_SERVICE_FILTERS: ServiceFilters = { q: '' }

/** Ventana de fechas que el contrato admite; espejo del `min`/`max` de los inputs. */
export const SERVICE_DATE_MIN = '1900-01-01'
export const SERVICE_DATE_MAX = '2999-12-31'
