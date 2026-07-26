import { z } from 'zod'

/** Tope de la búsqueda libre, espejo del `maxLength` del contrato. */
export const SEARCH_MAX_LENGTH = 200

/**
 * Filtros del listado de existencias. No es un form clásico: tipa y normaliza
 * el estado local de la barra de filtros.
 *
 * `q` se valida "soft" (acepta 1-2 chars en el input por UX); el gate de
 * `minLength=3` para golpear el backend vive en el hook (regla del proyecto
 * frontend_search_minlength_3). El máximo espeja el `maxLength: 200` del contrato.
 *
 * No hay filtro de estado activo/inactivo: la pantalla lista SOLO productos
 * activos (`isActive=true` fijo en el hook), de modo que el total de la tabla
 * sin filtros cuadra con el KPI "Productos activos", definido sobre activos.
 */
export const stockFiltersSchema = z.object({
  q: z.string().max(SEARCH_MAX_LENGTH).default(''),
  categoryId: z.number().int().positive().optional(),
  lowOnly: z.boolean().default(false),
})

export type StockFilters = z.infer<typeof stockFiltersSchema>

/** Estado inicial: sin filtros (la búsqueda arranca vacía, sin el corte de stock bajo). */
export const EMPTY_STOCK_FILTERS: StockFilters = { q: '', lowOnly: false }
