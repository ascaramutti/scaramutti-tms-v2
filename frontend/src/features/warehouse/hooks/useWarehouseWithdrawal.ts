import { useQuery } from '@tanstack/react-query'
import { getWarehouseWithdrawal, type WarehouseWithdrawalResponse } from '../../../api'
import { readEtag, type WithEtag } from '../../../shared/utils/etag'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { warehouseKeys } from '../queryKeys'

/** Retiro + el ETag opaco del header, que es lo que exige el `If-Match` del cancel. */
export type WarehouseWithdrawalWithEtag = WithEtag<WarehouseWithdrawalResponse>

async function fetchWithdrawal(id: number): Promise<WarehouseWithdrawalWithEtag> {
  const { data, headers } = await getWarehouseWithdrawal({ path: { id }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/withdrawals/{id}')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Detalle de un retiro + su ETag para el `If-Match` de la anulación. Hereda el
 * `staleTime` global: el estado puede cambiar (una anulación en otra pestaña), así
 * que conviene refrescar al revisitar.
 *
 * No reintenta ante 404 para que el estado "no encontrado" aparezca de inmediato.
 */
export function useWarehouseWithdrawal(id: number) {
  return useQuery({
    queryKey: warehouseKeys.withdrawalDetail(id),
    queryFn: () => fetchWithdrawal(id),
    enabled: Number.isInteger(id) && id > 0,
    retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
  })
}
