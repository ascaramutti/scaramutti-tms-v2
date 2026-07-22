import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  listWarehouseWithdrawals,
  type ListWarehouseWithdrawalsData,
  type PageOfWarehouseWithdrawal,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'
import type { WithdrawalFilters } from '../schemas/withdrawal-filters.schema'

type WithdrawalListQuery = NonNullable<ListWarehouseWithdrawalsData['query']>

interface UseWarehouseWithdrawalsListParams {
  page: number
  size: number
  filters: WithdrawalFilters
}

/**
 * Construye el objeto de query del backend a partir de los filtros de UI. Los
 * filtros vacíos se OMITEN (no viajan): omitirlos equivale a "sin filtro" y
 * mantiene limpia la key del cache. La unidad de flota ya viene desglosada en el
 * trío disyunto (a lo sumo uno seteado).
 */
export function buildQuery({
  page,
  size,
  filters,
}: UseWarehouseWithdrawalsListParams): WithdrawalListQuery {
  const query: WithdrawalListQuery = { page, size }
  if (filters.status) query.status = filters.status
  if (filters.productId) query.productId = filters.productId
  if (filters.receivedByWorkerId) query.receivedByWorkerId = filters.receivedByWorkerId
  if (filters.tractorId) query.tractorId = filters.tractorId
  if (filters.trailerId) query.trailerId = filters.trailerId
  if (filters.escortVehicleId) query.escortVehicleId = filters.escortVehicleId
  if (filters.dateFrom) query.dateFrom = filters.dateFrom
  if (filters.dateTo) query.dateTo = filters.dateTo
  return query
}

async function fetchWarehouseWithdrawals(
  query: WithdrawalListQuery,
): Promise<PageOfWarehouseWithdrawal> {
  const { data } = await listWarehouseWithdrawals({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/withdrawals')
  }
  return data
}

/**
 * Lista los retiros, paginados y filtrados. `keepPreviousData` mantiene la tabla
 * anterior visible mientras se pagina o filtra (evita el parpadeo a spinner).
 */
export function useWarehouseWithdrawalsList(params: UseWarehouseWithdrawalsListParams) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: warehouseKeys.withdrawalList(query),
    queryFn: () => fetchWarehouseWithdrawals(query),
    placeholderData: keepPreviousData,
  })
}
