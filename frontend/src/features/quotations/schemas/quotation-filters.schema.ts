import { z } from 'zod'

// Enums espejados del contrato (QuotationStatus / QuotationType en el OpenAPI).
// Si el contrato agrega valores, actualizar acá también.
export const QUOTATION_STATUS_VALUES = ['DRAFT', 'SENT'] as const
export const QUOTATION_TYPE_VALUES = ['TRANSPORTE', 'ALQUILER'] as const

/**
 * Filtros del listado de cotizaciones. No es un form clásico: tipa y normaliza
 * el estado local de la barra de filtros.
 *
 * `q` se valida "soft" (acepta 1-2 chars en el input por UX); el gate de
 * `minLength=3` para golpear el backend vive en el hook (regla del proyecto
 * frontend_search_minlength_3). El rango de fechas se valida inline en la barra.
 */
export const quotationFiltersSchema = z.object({
  q: z.string().max(255).default(''),
  status: z.enum(QUOTATION_STATUS_VALUES).optional(),
  quotationType: z.enum(QUOTATION_TYPE_VALUES).optional(),
  dateFrom: z.string().optional(),
  dateTo: z.string().optional(),
})

export type QuotationFilters = z.infer<typeof quotationFiltersSchema>

/** Estado inicial: sin filtros (la búsqueda arranca vacía). */
export const EMPTY_QUOTATION_FILTERS: QuotationFilters = { q: '' }

/**
 * `true` si el rango de fechas es válido: alguna fecha ausente, o `desde <= hasta`.
 * Las fechas son ISO `yyyy-mm-dd`, que comparan lexicográficamente igual que
 * cronológicamente. Única fuente de verdad para la barra de filtros (mostrar el
 * error) y el hook (no disparar la request con un rango invertido).
 */
export function isValidDateRange(dateFrom?: string, dateTo?: string): boolean {
  return !dateFrom || !dateTo || dateFrom <= dateTo
}
