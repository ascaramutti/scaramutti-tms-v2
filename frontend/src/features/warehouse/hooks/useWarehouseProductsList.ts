import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  listWarehouseProducts,
  type ListWarehouseProductsData,
  type PageOfWarehouseProduct,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'
import type { StockFilters } from '../schemas/stock-filters.schema'

/** Mínimo de caracteres para que la búsqueda libre `q` golpee el backend. */
export const SEARCH_MIN_LENGTH = 3

type WarehouseProductListQuery = NonNullable<ListWarehouseProductsData['query']>

interface UseWarehouseProductsListParams {
  page: number
  size: number
  filters: StockFilters
}

/**
 * Construye el objeto de query para el backend a partir de los filtros de UI.
 * - `q` solo se incluye si tiene >= SEARCH_MIN_LENGTH chars (regla del proyecto).
 * - `lowOnly` se OMITE cuando es `false`: omitirlo equivale al default `false`
 *   del contrato y mantiene limpias la URL y la key del cache.
 * - `isActive` va fijo en `true`: Existencias muestra el inventario vigente, y
 *   así el total de la tabla sin filtros cuadra con el KPI "Productos activos".
 */
export function buildQuery({
  page,
  size,
  filters,
}: UseWarehouseProductsListParams): WarehouseProductListQuery {
  const query: WarehouseProductListQuery = { page, size, isActive: true }
  const trimmedQ = filters.q.trim()
  if (trimmedQ.length >= SEARCH_MIN_LENGTH) query.q = trimmedQ
  if (filters.categoryId) query.categoryId = filters.categoryId
  if (filters.lowOnly) query.lowOnly = true
  return query
}

async function fetchWarehouseProducts(
  query: WarehouseProductListQuery,
): Promise<PageOfWarehouseProduct> {
  const { data } = await listWarehouseProducts({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/products')
  }
  return data
}

/**
 * Lista productos con su stock, paginados y filtrados. `keepPreviousData`
 * mantiene la tabla anterior visible mientras se pagina/filtra (evita el
 * parpadeo a spinner).
 */
export function useWarehouseProductsList(params: UseWarehouseProductsListParams) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: warehouseKeys.productList(query),
    queryFn: () => fetchWarehouseProducts(query),
    placeholderData: keepPreviousData,
  })
}
