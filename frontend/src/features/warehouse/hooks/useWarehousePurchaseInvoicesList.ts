import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  listWarehousePurchaseInvoices,
  type ListWarehousePurchaseInvoicesData,
  type PageOfWarehousePurchaseInvoiceSummary,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'
import type { EntryFilters } from '../schemas/entry-filters.schema'

/** Mínimo de caracteres para que la búsqueda libre `q` golpee el backend. */
export const ENTRY_SEARCH_MIN_LENGTH = 3

type PurchaseInvoiceListQuery = NonNullable<ListWarehousePurchaseInvoicesData['query']>

interface UseWarehousePurchaseInvoicesListParams {
  page: number
  size: number
  filters: EntryFilters
}

/**
 * Construye el objeto de query del backend a partir de los filtros de UI. Los
 * filtros vacíos se OMITEN (no viajan como cadena vacía): omitirlos equivale a
 * "sin filtro" y mantiene limpia la key del cache.
 */
export function buildQuery({
  page,
  size,
  filters,
}: UseWarehousePurchaseInvoicesListParams): PurchaseInvoiceListQuery {
  const query: PurchaseInvoiceListQuery = { page, size }
  const trimmedQ = filters.q.trim()
  if (trimmedQ.length >= ENTRY_SEARCH_MIN_LENGTH) query.q = trimmedQ
  if (filters.status) query.status = filters.status
  if (filters.dateFrom) query.dateFrom = filters.dateFrom
  if (filters.dateTo) query.dateTo = filters.dateTo
  return query
}

async function fetchWarehousePurchaseInvoices(
  query: PurchaseInvoiceListQuery,
): Promise<PageOfWarehousePurchaseInvoiceSummary> {
  const { data } = await listWarehousePurchaseInvoices({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/purchase-invoices')
  }
  return data
}

/**
 * Lista las entradas (facturas de compra), paginadas y filtradas.
 * `keepPreviousData` mantiene la tabla anterior visible mientras se pagina o
 * filtra (evita el parpadeo a spinner).
 */
export function useWarehousePurchaseInvoicesList(
  params: UseWarehousePurchaseInvoicesListParams,
) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: warehouseKeys.purchaseInvoiceList(query),
    queryFn: () => fetchWarehousePurchaseInvoices(query),
    placeholderData: keepPreviousData,
  })
}
