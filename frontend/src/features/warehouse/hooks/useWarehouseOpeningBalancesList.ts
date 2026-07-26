import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  listWarehouseOpeningBalances,
  type ListWarehouseOpeningBalancesData,
  type PageOfWarehouseOpeningBalance,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'

type OpeningBalanceListQuery = NonNullable<ListWarehouseOpeningBalancesData['query']>

interface UseWarehouseOpeningBalancesListParams {
  page: number
  size: number
  /** Único filtro del contrato: acota el listado a un producto. */
  productId?: number
}

/**
 * Construye el objeto de query del backend. El producto sin elegir se OMITE (no
 * viaja): equivale a "sin filtro" y mantiene limpia la key del cache.
 */
export function buildQuery({
  page,
  size,
  productId,
}: UseWarehouseOpeningBalancesListParams): OpeningBalanceListQuery {
  const query: OpeningBalanceListQuery = { page, size }
  if (productId) query.productId = productId
  return query
}

async function fetchWarehouseOpeningBalances(
  query: OpeningBalanceListQuery,
): Promise<PageOfWarehouseOpeningBalance> {
  const { data } = await listWarehouseOpeningBalances({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/opening-balances')
  }
  return data
}

/**
 * Lista los cortes iniciales ya registrados, paginados. `keepPreviousData`
 * mantiene la tabla anterior visible mientras se pagina o filtra (evita el
 * parpadeo a spinner).
 */
export function useWarehouseOpeningBalancesList(
  params: UseWarehouseOpeningBalancesListParams,
) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: warehouseKeys.openingBalanceList(query),
    queryFn: () => fetchWarehouseOpeningBalances(query),
    placeholderData: keepPreviousData,
  })
}
