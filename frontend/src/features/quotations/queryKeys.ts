import type { ListQuotationsData } from '../../api'

type QuotationListParams = NonNullable<ListQuotationsData['query']>

/**
 * Query keys del dominio Cotizaciones. Factory centralizado para que las
 * invalidaciones y los caches usen siempre la misma forma de key.
 */
export const quotationKeys = {
  all: ['quotations'] as const,
  lists: () => [...quotationKeys.all, 'list'] as const,
  list: (params: QuotationListParams) => [...quotationKeys.lists(), params] as const,
  // Listo para el detalle (próxima pantalla del backlog).
  detail: (id: number) => [...quotationKeys.all, 'detail', id] as const,
}
