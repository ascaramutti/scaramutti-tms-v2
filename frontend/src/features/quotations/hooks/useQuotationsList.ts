import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listQuotations, type ListQuotationsData, type PageOfQuotationSummary } from '../../../api'
import { quotationKeys } from '../queryKeys'
import { isValidDateRange, type QuotationFilters } from '../schemas/quotation-filters.schema'

/** Mínimo de caracteres para que la búsqueda libre `q` golpee el backend. */
export const SEARCH_MIN_LENGTH = 3

type QuotationListQuery = NonNullable<ListQuotationsData['query']>

interface UseQuotationsListParams {
  page: number
  size: number
  filters: QuotationFilters
}

/**
 * Construye el objeto de query para el backend a partir de los filtros de UI.
 * - Omite claves vacías/undefined (no manda params vacíos).
 * - `q` solo se incluye si tiene >= SEARCH_MIN_LENGTH chars (regla del proyecto).
 */
function buildQuery({ page, size, filters }: UseQuotationsListParams): QuotationListQuery {
  const query: QuotationListQuery = { page, size }
  const trimmedQ = filters.q.trim()
  if (trimmedQ.length >= SEARCH_MIN_LENGTH) query.q = trimmedQ
  if (filters.status) query.status = filters.status
  if (filters.quotationType) query.quotationType = filters.quotationType
  // Las fechas solo se envían si el rango es válido. Un rango invertido muestra
  // el error en la UI y NO dispara una request con filtros rotos (evita un 400
  // o resultados vacíos engañosos mientras el usuario corrige).
  if (isValidDateRange(filters.dateFrom, filters.dateTo)) {
    if (filters.dateFrom) query.dateFrom = filters.dateFrom
    if (filters.dateTo) query.dateTo = filters.dateTo
  }
  return query
}

async function fetchQuotations(query: QuotationListQuery): Promise<PageOfQuotationSummary> {
  const { data } = await listQuotations({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotations')
  }
  return data
}

/**
 * Lista cotizaciones paginadas con filtros. `keepPreviousData` mantiene la tabla
 * anterior visible mientras se pagina/filtra (evita el parpadeo a spinner).
 */
export function useQuotationsList(params: UseQuotationsListParams) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: quotationKeys.list(query),
    queryFn: () => fetchQuotations(query),
    placeholderData: keepPreviousData,
  })
}
