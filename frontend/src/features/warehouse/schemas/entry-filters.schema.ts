import { z } from 'zod'

/** Tope de la búsqueda libre, espejo del `maxLength` del contrato. */
export const ENTRY_SEARCH_MAX_LENGTH = 200

/**
 * Filtros del listado de entradas. Como en Existencias, no es un form clásico:
 * tipa y normaliza el estado local de la barra de filtros.
 *
 * `q` se valida "soft" (el input acepta 1-2 chars por UX); el gate de
 * `minLength=3` para golpear el backend vive en el hook.
 *
 * No hay filtro por proveedor: `q` ya busca por número de factura, guía y nombre
 * del proveedor, así que un combobox aparte solo para filtrar sería redundante.
 */
export const entryFiltersSchema = z.object({
  q: z.string().max(ENTRY_SEARCH_MAX_LENGTH).default(''),
  status: z.enum(['ACTIVE', 'CANCELLED']).optional(),
  /** `invoiceDate` desde/hasta, inclusive, en formato `YYYY-MM-DD`. */
  dateFrom: z.string().optional(),
  dateTo: z.string().optional(),
})

export type EntryFilters = z.infer<typeof entryFiltersSchema>

/** Estado inicial: sin filtros (todas las entradas, activas y anuladas). */
export const EMPTY_ENTRY_FILTERS: EntryFilters = { q: '' }
