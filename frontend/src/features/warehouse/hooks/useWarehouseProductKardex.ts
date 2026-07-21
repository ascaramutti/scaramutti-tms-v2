import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  getWarehouseProductKardex,
  type GetWarehouseProductKardexData,
  type PageOfWarehouseKardexMovement,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'

type WarehouseKardexQuery = NonNullable<GetWarehouseProductKardexData['query']>

interface UseWarehouseProductKardexParams {
  productId: number
  page: number
  size: number
  /** La pantalla lo apaga cuando ya sabe que el producto no existe. */
  enabled: boolean
}

/**
 * Query del kardex. Sin filtro de fechas en esta entrega (el contrato ofrece
 * `dateFrom`/`dateTo`, se evalúan cuando haya volumen real por producto).
 */
export function buildKardexQuery({
  page,
  size,
}: Pick<UseWarehouseProductKardexParams, 'page' | 'size'>): WarehouseKardexQuery {
  return { page, size }
}

async function fetchWarehouseProductKardex(
  productId: number,
  query: WarehouseKardexQuery,
): Promise<PageOfWarehouseKardexMovement> {
  const { data } = await getWarehouseProductKardex({
    path: { id: productId },
    query,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/products/{id}/kardex')
  }
  return data
}

/**
 * Movimientos del producto con su saldo corrido, paginados. `keepPreviousData`
 * evita el parpadeo a spinner al cambiar de página.
 */
export function useWarehouseProductKardex({
  productId,
  page,
  size,
  enabled,
}: UseWarehouseProductKardexParams) {
  const query = buildKardexQuery({ page, size })
  return useQuery({
    queryKey: warehouseKeys.kardex(productId, query),
    queryFn: () => fetchWarehouseProductKardex(productId, query),
    enabled: enabled && Number.isInteger(productId) && productId > 0,
    placeholderData: keepPreviousData,
  })
}
